package kz.alimbetov.akmai.knowledge.lifecycle;

import java.util.Map;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.knowledge.audit.AuditEventRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationReconciliationService {

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
                SELECT g.document_id, g.generation, g.access_level
                FROM knowledge_document_generation g
                WHERE g.generation_status IN ('RETIRED', 'FAILED')
                  AND g.cleanup_required
                  AND COALESCE(
                          g.retired_at,
                          g.failed_at,
                          g.started_at
                      ) < clock_timestamp()
                          - (? * interval '1 millisecond')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM knowledge_document_lifecycle l
                      WHERE l.document_id = g.document_id
                        AND l.published_generation = g.generation
                  )
                ORDER BY COALESCE(g.retired_at, g.failed_at, g.started_at),
                         g.document_id, g.generation
                LIMIT ?
                """,
                (rs, rowNum) -> new GenerationKey(
                        rs.getString("document_id"),
                        rs.getLong("generation"),
                        rs.getLong("access_level")
                ),
                properties.gracePeriod().toMillis(),
                properties.batchSize()
        );

        int cleaned = 0;
        for (GenerationKey candidate : candidates) {
            Boolean result = transactionTemplate.execute(status ->
                    reconcileOne(candidate)
            );
            if (Boolean.TRUE.equals(result)) {
                cleaned++;
            }
        }

        purgeExpiredTombstones();
        return cleaned;
    }

    private boolean reconcileOne(GenerationKey key) {
        Long publishedGeneration = jdbcTemplate.query(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> (Long) rs.getObject("published_generation"),
                key.documentId()
        ).stream().findFirst().orElse(null);
        if (publishedGeneration != null
                && publishedGeneration == key.generation()) {
            return false;
        }

        GenerationRow row = jdbcTemplate.query(
                """
                SELECT generation_status, embedding_profile_id
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new GenerationRow(
                        rs.getString("generation_status"),
                        rs.getString("embedding_profile_id")
                ),
                key.documentId(),
                key.generation()
        ).stream().findFirst().orElse(null);

        if (row == null
                || (!"RETIRED".equals(row.status())
                && !"FAILED".equals(row.status()))) {
            return false;
        }

        GenerationIdentity identity = new GenerationIdentity(
                key.documentId(),
                key.generation(),
                key.accessLevel()
        );

        if (row.profileId() == null || row.profileId().isBlank()) {
            throw new IllegalStateException(
                    "Generation has no embedding profile: "
                            + key.documentId()
                            + "/"
                            + key.generation()
            );
        }

        EmbeddingProfile profile = profiles.findById(row.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing embedding profile " + row.profileId()
                ));

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
            if (verified != 1) {
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
                    throw new IllegalStateException(
                            "Retired generation tombstone is missing"
                    );
                }
            }
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

        return jdbcTemplate.update(
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
        ) == 1;
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
                manifestRows
        );
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
            long accessLevel
    ) {
    }

    private record GenerationRow(String status, String profileId) {
    }

    private record ResidualCounts(
            int vectors,
            int projections,
            int identifiers,
            int referenceTargets,
            int referenceEdges,
            int manifests
    ) {
        int total() {
            return vectors
                    + projections
                    + identifiers
                    + referenceTargets
                    + referenceEdges
                    + manifests;
        }
    }
}
