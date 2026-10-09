package kz.alimbetov.akmai.knowledge.lifecycle;

import java.util.Map;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.knowledge.audit.AuditEventRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationReconciliationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GenerationReconciliationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PostgresGenerationVectorRepository vectors;
    private final EmbeddingProfileRepository profiles;
    private final GenerationRepairService repairService;
    private final ReconciliationProperties properties;
    private final AuditEventRepository audit;

    public GenerationReconciliationService(
            JdbcTemplate jdbcTemplate,
            @Qualifier("cleanupTransactionTemplate")
            TransactionTemplate transactionTemplate,
            PostgresGenerationVectorRepository vectors,
            EmbeddingProfileRepository profiles,
            GenerationRepairService repairService,
            ReconciliationProperties properties,
            AuditEventRepository audit
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.vectors = vectors;
        this.profiles = profiles;
        this.repairService = repairService;
        this.properties = properties;
        this.audit = audit;
    }

    public int reconcileBatch() {
        if (!properties.enabled()) {
            return 0;
        }

        var candidates = jdbcTemplate.query(
                """
                SELECT g.document_id,
                       g.generation,
                       g.access_level,
                       g.generation_status
                FROM knowledge_document_generation g
                WHERE g.generation_status IN (
                    'RETIRING',
                    'RETIRED',
                    'FAILED'
                )
                  AND g.cleanup_required
                  AND (
                      (
                          g.generation_status = 'RETIRING'
                          AND COALESCE(g.retired_at, g.started_at)
                              < clock_timestamp()
                      )
                      OR
                      (
                          g.generation_status IN ('RETIRED', 'FAILED')
                          AND COALESCE(
                                  g.retired_at,
                                  g.failed_at,
                                  g.started_at
                              ) < clock_timestamp()
                                  - (? * interval '1 millisecond')
                      )
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM knowledge_document_lifecycle l
                      WHERE l.document_id = g.document_id
                        AND l.published_generation = g.generation
                  )
                ORDER BY COALESCE(
                             g.retired_at,
                             g.failed_at,
                             g.started_at
                         ),
                         g.document_id,
                         g.generation
                LIMIT ?
                """,
                (rs, rowNum) -> new GenerationKey(
                        rs.getString("document_id"),
                        rs.getLong("generation"),
                        rs.getLong("access_level"),
                        rs.getString("generation_status")
                ),
                properties.gracePeriod().toMillis(),
                properties.batchSize()
        );

        int cleaned = 0;
        for (GenerationKey candidate : candidates) {
            try {
                cleaned += reconcileCandidate(candidate);
            } catch (CandidateStateException exception) {
                recordCandidateFailure(candidate, exception);
            }
        }

        purgeExpiredTombstones();
        return cleaned;
    }

    private int reconcileCandidate(GenerationKey candidate) {
        if ("RETIRING".equals(candidate.status())) {
            boolean prepared = Boolean.TRUE.equals(
                    transactionTemplate.execute(status -> {
                        if (!tryCandidateLock(candidate)) {
                            logCandidateBusy(candidate, "prepare");
                            return false;
                        }
                        return prepareRetiring(candidate);
                    })
            );
            if (prepared) {
                transactionTemplate.execute(status -> {
                    if (!tryCandidateLock(candidate)) {
                        logCandidateBusy(candidate, "purge");
                        return null;
                    }
                    purgeRetiring(candidate);
                    return null;
                });
            }
            return 0;
        }

        Boolean result = transactionTemplate.execute(status -> {
            if (!tryCandidateLock(candidate)) {
                logCandidateBusy(candidate, "terminal");
                return false;
            }
            return reconcileTerminal(candidate);
        });
        return Boolean.TRUE.equals(result) ? 1 : 0;
    }

    private void recordCandidateFailure(
            GenerationKey candidate,
            CandidateStateException exception
    ) {
        String error = candidateError(exception);
        LOGGER.warn(
                "generation_reconciliation event=candidate_state_failed documentId={} generation={} status={} error={}",
                candidate.documentId(),
                candidate.generation(),
                candidate.status(),
                error
        );
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(
                        """
                        UPDATE knowledge_document_generation
                        SET last_error = ?
                        WHERE document_id = ?
                          AND generation = ?
                          AND cleanup_required
                        """,
                        error,
                        candidate.documentId(),
                        candidate.generation()
                );
                audit.append(
                        "GENERATION_RECONCILIATION_FAILED",
                        identity(candidate),
                        null,
                        "generation-reconciler",
                        Map.of(
                                "generationStatus", candidate.status(),
                                "errorType",
                                exception.getClass().getSimpleName(),
                                "error", error
                        )
                );
            });
        } catch (RuntimeException bookkeepingFailure) {
            LOGGER.error(
                    "generation_reconciliation event=candidate_failure_bookkeeping_failed documentId={} generation={} errorType={}",
                    candidate.documentId(),
                    candidate.generation(),
                    bookkeepingFailure.getClass().getSimpleName(),
                    bookkeepingFailure
            );
        }
    }

    private String candidateError(RuntimeException exception) {
        String message = exception.getMessage();
        String value = message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private boolean tryCandidateLock(GenerationKey key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Generation reconciliation advisory lock requires an active transaction"
            );
        }
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class,
                candidateLockKey(key)
        );
        return Boolean.TRUE.equals(acquired);
    }

    private long candidateLockKey(GenerationKey key) {
        long documentHash = Integer.toUnsignedLong(key.documentId().hashCode());
        long generationHash = Integer.toUnsignedLong(
                Long.hashCode(key.generation())
        );
        return (documentHash << 32) | generationHash;
    }

    private void logCandidateBusy(GenerationKey key, String phase) {
        LOGGER.debug(
                "generation_reconciliation event=candidate_busy phase={} documentId={} generation={}",
                phase,
                key.documentId(),
                key.generation()
        );
    }

    private boolean prepareRetiring(GenerationKey key) {
        LifecycleFence lifecycle = lockLifecycle(key.documentId());
        if (lifecycle == null
                || sameGeneration(
                        lifecycle.publishedGeneration(),
                        key.generation()
                )) {
            return false;
        }

        GenerationRow row = lockGeneration(key);
        if (row == null || !"RETIRING".equals(row.status())) {
            return false;
        }
        if (!row.cleanupRequired()) {
            throw new CandidateStateException(
                    "Retiring generation is not marked for cleanup"
            );
        }

        EmbeddingProfile profile = profile(row.profileId(), key);
        GenerationIdentity identity = identity(key);

        int projectionCount = count(
                "knowledge_search_projection",
                identity
        );
        int vectorCount = vectors.countGeneration(profile, identity);

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO knowledge_retired_generation (
                    document_id,
                    generation,
                    access_level,
                    embedding_profile_id,
                    projection_count,
                    vector_count,
                    content_fingerprint,
                    physical_id_version,
                    retention_policy,
                    purge_started_at,
                    cleanup_status,
                    cleanup_attempts
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    clock_timestamp(),
                    'PURGING',
                    1
                )
                ON CONFLICT (
                    document_id,
                    generation,
                    access_level
                ) DO NOTHING
                """,
                key.documentId(),
                key.generation(),
                key.accessLevel(),
                row.profileId(),
                projectionCount,
                vectorCount,
                row.contentFingerprint(),
                row.physicalIdVersion(),
                lifecycle.lifecyclePolicy()
        );

        if (inserted == 0) {
            int retry = jdbcTemplate.update(
                    """
                    UPDATE knowledge_retired_generation
                    SET cleanup_attempts = cleanup_attempts + 1,
                        purge_started_at = clock_timestamp(),
                        last_error = NULL
                    WHERE document_id = ?
                      AND generation = ?
                      AND access_level = ?
                      AND cleanup_status = 'PURGING'
                      AND purge_started_at <= clock_timestamp()
                          - (? * interval '1 millisecond')
                    """,
                    key.documentId(),
                    key.generation(),
                    key.accessLevel(),
                    stalePurgeMillis()
            );
            if (retry != 1) {
                String status = jdbcTemplate.queryForObject(
                        """
                        SELECT cleanup_status
                        FROM knowledge_retired_generation
                        WHERE document_id = ?
                          AND generation = ?
                          AND access_level = ?
                        """,
                        String.class,
                        key.documentId(),
                        key.generation(),
                        key.accessLevel()
                );
                if ("PURGING".equals(status)) {
                    LOGGER.debug(
                            "generation_reconciliation event=fresh_purge_claim documentId={} generation={}",
                            key.documentId(),
                            key.generation()
                    );
                    return false;
                }
                throw new CandidateStateException(
                        "Retiring generation has incompatible tombstone state: "
                                + status
                );
            }
            LOGGER.info(
                    "generation_reconciliation event=stale_purge_reclaimed documentId={} generation={}",
                    key.documentId(),
                    key.generation()
            );
        } else {
            audit.append(
                    "GENERATION_PURGE_STARTED",
                    identity,
                    null,
                    "generation-reconciler",
                    Map.of(
                            "projectionCount", projectionCount,
                            "vectorCount", vectorCount
                    )
            );
        }

        return true;
    }

    private long stalePurgeMillis() {
        return Math.max(
                properties.gracePeriod().toMillis(),
                properties.fixedDelay().toMillis()
        );
    }

    private void purgeRetiring(GenerationKey key) {
        LifecycleFence lifecycle = lockLifecycle(key.documentId());
        if (lifecycle == null
                || sameGeneration(
                        lifecycle.publishedGeneration(),
                        key.generation()
                )) {
            return;
        }

        GenerationRow row = lockGeneration(key);
        if (row == null || !"RETIRING".equals(row.status())) {
            return;
        }

        EmbeddingProfile profile = profile(row.profileId(), key);
        GenerationIdentity identity = identity(key);

        GenerationRepairService.RepairOutcome purge =
                repairService.repair(profile, identity);
        ResidualCounts after = residualCounts(profile, identity);

        if (after.total() != 0) {
            String error = "Generation purge deferred after "
                    + purge.batches()
                    + " bounded batches";
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation
                    SET last_error = ?
                    WHERE document_id = ?
                      AND generation = ?
                      AND generation_status = 'RETIRING'
                    """,
                    error,
                    key.documentId(),
                    key.generation()
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_retired_generation
                    SET last_error = ?
                    WHERE document_id = ?
                      AND generation = ?
                      AND access_level = ?
                      AND cleanup_status = 'PURGING'
                    """,
                    error,
                    key.documentId(),
                    key.generation(),
                    key.accessLevel()
            );
            audit.append(
                    "GENERATION_PURGE_DEFERRED",
                    identity,
                    null,
                    "generation-reconciler",
                    Map.of(
                            "residualRows", after.total(),
                            "deletedRows", purge.deletedRows(),
                            "purgeBatches", purge.batches(),
                            "batchLimitReached",
                            purge.batchLimitReached()
                    )
            );
            return;
        }

        int purged = jdbcTemplate.update(
                """
                UPDATE knowledge_retired_generation
                SET cleanup_status = 'PURGED',
                    retired_at = clock_timestamp(),
                    purge_after = clock_timestamp() + interval '7 days',
                    last_error = NULL
                WHERE document_id = ?
                  AND generation = ?
                  AND access_level = ?
                  AND cleanup_status = 'PURGING'
                """,
                key.documentId(),
                key.generation(),
                key.accessLevel()
        );
        if (purged != 1) {
            throw new CandidateStateException(
                    "Retiring generation tombstone cannot be finalized"
            );
        }

        int retired = jdbcTemplate.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRED',
                    retired_at = COALESCE(
                        retired_at,
                        clock_timestamp()
                    ),
                    cleanup_required = true,
                    last_error = NULL
                WHERE document_id = ?
                  AND generation = ?
                  AND generation_status = 'RETIRING'
                """,
                key.documentId(),
                key.generation()
        );
        if (retired != 1) {
            throw new CandidateStateException(
                    "Retiring generation state changed during purge"
            );
        }

        audit.append(
                "HOT_PAYLOAD_PURGED",
                identity,
                null,
                "generation-reconciler",
                Map.of(
                        "deletedRows", purge.deletedRows(),
                        "purgeBatches", purge.batches()
                )
        );
    }

    private boolean reconcileTerminal(GenerationKey key) {
        Long publishedGeneration = publishedGenerationForUpdate(
                key.documentId()
        );
        if (sameGeneration(publishedGeneration, key.generation())) {
            return false;
        }

        GenerationRow row = lockGeneration(key);
        if (row == null
                || (!"RETIRED".equals(row.status())
                && !"FAILED".equals(row.status()))) {
            return false;
        }

        GenerationIdentity identity = identity(key);
        EmbeddingProfile profile = profile(row.profileId(), key);

        ResidualCounts before = residualCounts(profile, identity);
        GenerationRepairService.RepairOutcome repair =
                before.total() == 0
                        ? new GenerationRepairService.RepairOutcome(
                                0L,
                                0,
                                false
                        )
                        : repairService.repair(profile, identity);

        ResidualCounts after = residualCounts(profile, identity);
        if (after.total() != 0) {
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation
                    SET last_error = ?
                    WHERE document_id = ?
                      AND generation = ?
                    """,
                    "Residual retrieval rows remain after "
                            + repair.batches()
                            + " repair batches",
                    key.documentId(),
                    key.generation()
            );
            audit.append(
                    "GENERATION_REPAIR_DEFERRED",
                    identity,
                    null,
                    "generation-reconciler",
                    Map.of(
                            "residualRowsBefore", before.total(),
                            "residualRowsAfter", after.total(),
                            "deletedRows", repair.deletedRows(),
                            "repairBatches", repair.batches(),
                            "batchLimitReached",
                            repair.batchLimitReached()
                    )
            );
            return false;
        }

        if ("RETIRED".equals(row.status())) {
            verifyTombstone(key);
        }

        int cleaned = jdbcTemplate.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'CLEANED',
                    cleaned_at = clock_timestamp(),
                    cleanup_required = false,
                    last_error = NULL
                WHERE document_id = ?
                  AND generation = ?
                  AND generation_status IN ('RETIRED', 'FAILED')
                """,
                key.documentId(),
                key.generation()
        );
        if (cleaned != 1) {
            throw new CandidateStateException(
                    "Terminal generation state changed before CLEANED transition"
            );
        }

        audit.append(
                before.total() == 0
                        ? "GENERATION_VERIFIED"
                        : "GENERATION_REPAIRED",
                identity,
                null,
                "generation-reconciler",
                Map.of(
                        "residualRowsBefore", before.total(),
                        "deletedRows", repair.deletedRows(),
                        "repairBatches", repair.batches(),
                        "generationStatus", row.status()
                )
        );
        return true;
    }

    private void verifyTombstone(GenerationKey key) {
        int verified = jdbcTemplate.update(
                """
                UPDATE knowledge_retired_generation
                SET cleanup_status = 'VERIFIED',
                    verified_at = clock_timestamp(),
                    cleanup_attempts = cleanup_attempts + 1,
                    last_error = NULL
                WHERE document_id = ?
                  AND generation = ?
                  AND access_level = ?
                  AND cleanup_status = 'PURGED'
                """,
                key.documentId(),
                key.generation(),
                key.accessLevel()
        );
        if (verified == 1) {
            return;
        }

        Integer alreadyVerified = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_retired_generation
                WHERE document_id = ?
                  AND generation = ?
                  AND access_level = ?
                  AND cleanup_status = 'VERIFIED'
                """,
                Integer.class,
                key.documentId(),
                key.generation(),
                key.accessLevel()
        );
        if (alreadyVerified == null || alreadyVerified != 1) {
            throw new CandidateStateException(
                    "Retired generation tombstone is missing"
            );
        }
    }

    private LifecycleFence lockLifecycle(String documentId) {
        return jdbcTemplate.query(
                """
                SELECT published_generation,
                       lifecycle_policy
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new LifecycleFence(
                        (Long) rs.getObject("published_generation"),
                        rs.getString("lifecycle_policy")
                ),
                documentId
        ).stream().findFirst().orElse(null);
    }

    private Long publishedGenerationForUpdate(String documentId) {
        LifecycleFence lifecycle = lockLifecycle(documentId);
        return lifecycle == null
                ? null
                : lifecycle.publishedGeneration();
    }

    private GenerationRow lockGeneration(GenerationKey key) {
        return jdbcTemplate.query(
                """
                SELECT generation_status,
                       embedding_profile_id,
                       content_fingerprint,
                       physical_id_version,
                       cleanup_required
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new GenerationRow(
                        rs.getString("generation_status"),
                        rs.getString("embedding_profile_id"),
                        rs.getString("content_fingerprint"),
                        rs.getShort("physical_id_version"),
                        rs.getBoolean("cleanup_required")
                ),
                key.documentId(),
                key.generation()
        ).stream().findFirst().orElse(null);
    }

    private EmbeddingProfile profile(
            String profileId,
            GenerationKey key
    ) {
        if (profileId == null || profileId.isBlank()) {
            throw new CandidateStateException(
                    "Generation has no embedding profile: "
                            + key.documentId()
                            + "/"
                            + key.generation()
            );
        }
        return profiles.findById(profileId)
                .orElseThrow(() -> new CandidateStateException(
                        "Missing embedding profile " + profileId
                ));
    }

    private GenerationIdentity identity(GenerationKey key) {
        return new GenerationIdentity(
                key.documentId(),
                key.generation(),
                key.accessLevel()
        );
    }

    private ResidualCounts residualCounts(
            EmbeddingProfile profile,
            GenerationIdentity identity
    ) {
        int vectorRows = vectors.countGeneration(profile, identity);
        int projectionRows = count(
                "knowledge_search_projection",
                identity
        );
        int identifierRows = count(
                "document_identifier",
                identity
        );
        int referenceTargetRows = count(
                "knowledge_reference_target",
                identity
        );
        int referenceEdgeRows = count(
                "knowledge_reference_edge",
                identity
        );
        int associationRows = countAssociations(identity);
        int manifestRows = count(
                "knowledge_document_vector_generation",
                identity
        );
        return new ResidualCounts(
                vectorRows,
                projectionRows,
                identifierRows,
                referenceTargetRows,
                referenceEdgeRows,
                associationRows,
                manifestRows
        );
    }

    private int countAssociations(GenerationIdentity identity) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE access_level = ?
                  AND (
                      (
                          source_document_id = ?
                          AND source_generation = ?
                      )
                      OR
                      (
                          target_document_id = ?
                          AND target_generation = ?
                      )
                  )
                """,
                Integer.class,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation(),
                identity.documentId(),
                identity.generation()
        );
        return value == null ? 0 : value;
    }

    private int count(String table, GenerationIdentity identity) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM %s
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """.formatted(table),
                Integer.class,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
        return value == null ? 0 : value;
    }

    private boolean sameGeneration(Long value, long generation) {
        return value != null && value == generation;
    }

    private void purgeExpiredTombstones() {
        jdbcTemplate.update(
                """
                WITH expired AS (
                    SELECT document_id, generation, access_level
                    FROM knowledge_retired_generation
                    WHERE cleanup_status = 'VERIFIED'
                      AND purge_after <= clock_timestamp()
                    ORDER BY purge_after, document_id, generation
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                DELETE FROM knowledge_retired_generation tombstone
                USING expired
                WHERE tombstone.document_id = expired.document_id
                  AND tombstone.generation = expired.generation
                  AND tombstone.access_level = expired.access_level
                """,
                properties.batchSize()
        );
    }

    private record GenerationKey(
            String documentId,
            long generation,
            long accessLevel,
            String status
    ) {
    }

    private record LifecycleFence(
            Long publishedGeneration,
            String lifecyclePolicy
    ) {
    }

    private record GenerationRow(
            String status,
            String profileId,
            String contentFingerprint,
            short physicalIdVersion,
            boolean cleanupRequired
    ) {
    }

    private record ResidualCounts(
            int vectors,
            int projections,
            int identifiers,
            int referenceTargets,
            int referenceEdges,
            int associations,
            int manifests
    ) {
        int total() {
            return vectors
                    + projections
                    + identifiers
                    + referenceTargets
                    + referenceEdges
                    + associations
                    + manifests;
        }
    }

    private static final class CandidateStateException
            extends IllegalStateException {

        CandidateStateException(String message) {
            super(message);
        }
    }
}
