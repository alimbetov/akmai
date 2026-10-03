package kz.alimbetov.akmai.knowledge.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.audit.AuditEventRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChunkRetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkRetentionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RetentionClaimRepository claimRepository;
    private final SearchProjectionRepository projectionRepository;
    private final DocumentIdentifierRepository identifierRepository;
    private final VectorGenerationRepository vectorGenerationRepository;
    private final PostgresGenerationVectorRepository vectorRepository;
    private final EmbeddingProfileRepository profileRepository;
    private final AuditEventRepository audit;
    private AkmaiMetrics metrics;

    public ChunkRetentionService(
            JdbcTemplate jdbcTemplate,
            @Qualifier("cleanupTransactionTemplate")
            TransactionTemplate transactionTemplate,
            RetentionClaimRepository claimRepository,
            SearchProjectionRepository projectionRepository,
            DocumentIdentifierRepository identifierRepository,
            VectorGenerationRepository vectorGenerationRepository,
            PostgresGenerationVectorRepository vectorRepository,
            EmbeddingProfileRepository profileRepository,
            AuditEventRepository audit
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.claimRepository = claimRepository;
        this.projectionRepository = projectionRepository;
        this.identifierRepository = identifierRepository;
        this.vectorGenerationRepository = vectorGenerationRepository;
        this.vectorRepository = vectorRepository;
        this.profileRepository = profileRepository;
        this.audit = audit;
    }

    @Autowired(required = false)
    void setMetrics(AkmaiMetrics metrics) {
        this.metrics = metrics;
    }

    public RetentionCleanupResult cleanup(RetentionClaim claim) {
        try {
            RetentionCleanupResult result = transactionTemplate.execute(
                    status -> cleanupInTransaction(claim)
            );
            RetentionCleanupResult observed =
                    result == null ? stale(claim) : result;
            observe(observed);
            return observed;
        } catch (StaleClaimException exception) {
            RetentionCleanupResult result = stale(claim);
            observe(result);
            return result;
        } catch (RuntimeException exception) {
            boolean failed = claimRepository.markFailed(
                    claim,
                    safeMessage(exception)
            );
            if (!failed && metrics != null) {
                metrics.retentionLeaseLost();
            }
            RetentionCleanupResult result = failed
                    ? new RetentionCleanupResult(
                            claim.documentId(),
                            claim.generation(),
                            0,
                            RetentionCleanupResult.Status.FAILED
                    )
                    : stale(claim);
            observe(result);
            return result;
        }
    }

    private RetentionCleanupResult cleanupInTransaction(RetentionClaim claim) {
        ClaimFence fence = lockFence(claim);
        if (!fence.valid()) {
            throw new StaleClaimException();
        }
        GenerationIdentity identity = new GenerationIdentity(
                claim.documentId(),
                claim.generation(),
                fence.accessLevel()
        );

        int marked = jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'DELETING',
                    lifecycle_status = 'DELETING',
                    delete_started_at = clock_timestamp(),
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE document_id = ?
                  AND claim_generation = ?
                  AND claim_id = ?
                  AND claimed_by = ?
                  AND lease_until > clock_timestamp()
                  AND retention_status = 'DELETE_PENDING'
                """,
                claim.documentId(),
                claim.generation(),
                claim.claimId(),
                claim.workerId()
        );
        if (marked != 1) {
            throw new StaleClaimException();
        }

        GenerationDescriptor generation = jdbcTemplate.query(
                """
                SELECT embedding_profile_id,
                       content_fingerprint,
                       physical_id_version,
                       chunk_count
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new GenerationDescriptor(
                        rs.getString("embedding_profile_id"),
                        rs.getString("content_fingerprint"),
                        rs.getShort("physical_id_version"),
                        (Integer) rs.getObject("chunk_count")
                ),
                claim.documentId(),
                claim.generation()
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "Generation does not exist for retention"
                )
        );

        if (generation.profileId() == null
                || generation.profileId().isBlank()) {
            throw new IllegalStateException(
                    "Generation has no verifiable embedding profile"
            );
        }
        EmbeddingProfile profile = profileRepository
                .findById(generation.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding profile is missing: "
                                + generation.profileId()
                ));

        List<String> vectorIds =
                vectorGenerationRepository.findVectorIds(identity);
        if (vectorIds.isEmpty()
                && generation.chunkCount() != null
                && generation.chunkCount() > 0) {
            throw new IllegalStateException(
                    "Vector manifest missing; reconciliation is required"
            );
        }

        Integer projectionCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_search_projection
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                Integer.class,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
        int expectedProjections =
                projectionCount == null ? 0 : projectionCount;
        if (generation.chunkCount() != null
                && generation.chunkCount() != expectedProjections) {
            throw new IllegalStateException(
                    "Projection count does not match generation chunk_count"
            );
        }
        if (vectorIds.size() != expectedProjections) {
            throw new IllegalStateException(
                    "Vector manifest count does not match projection count"
            );
        }

        int deletedVectors = vectorRepository.deleteGeneration(
                profile,
                identity
        );
        if (deletedVectors != vectorIds.size()) {
            throw new IllegalStateException(
                    "Vector delete count does not match generation manifest"
            );
        }
        if (vectorRepository.countGeneration(profile, identity) != 0) {
            throw new IllegalStateException(
                    "Vectors remain after hot generation purge"
            );
        }

        int deletedProjections =
                projectionRepository.deleteGenerationCount(identity);
        if (deletedProjections != expectedProjections) {
            throw new IllegalStateException(
                    "Projection delete count does not match generation"
            );
        }

        jdbcTemplate.update(
                """
                DELETE FROM knowledge_reference_edge
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_reference_target
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
        identifierRepository.deleteGeneration(identity);
        vectorGenerationRepository.deleteGeneration(identity);

        if (!finalFenceValid(claim)) {
            throw new StaleClaimException();
        }

        int tombstone = jdbcTemplate.update(
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
                    retired_at,
                    purge_after,
                    cleanup_status
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    clock_timestamp(),
                    clock_timestamp() + interval '7 days',
                    'PENDING_VERIFY'
                )
                ON CONFLICT (document_id, generation) DO NOTHING
                """,
                identity.documentId(),
                identity.generation(),
                identity.accessLevel(),
                generation.profileId(),
                deletedProjections,
                deletedVectors,
                generation.contentFingerprint(),
                generation.physicalIdVersion(),
                fence.lifecyclePolicy()
        );
        if (tombstone != 1) {
            throw new IllegalStateException(
                    "Retired generation tombstone already exists"
            );
        }

        audit.append(
                "HOT_PAYLOAD_PURGED",
                identity,
                claim.claimId(),
                "retention-worker",
                Map.of(
                        "projectionCount", deletedProjections,
                        "vectorCount", deletedVectors
                )
        );

        int retired = jdbcTemplate.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRED',
                    retired_at = COALESCE(retired_at, clock_timestamp()),
                    cleanup_required = true
                WHERE document_id = ?
                  AND generation = ?
                  AND generation_status = 'PUBLISHED'
                """,
                claim.documentId(),
                claim.generation()
        );
        if (retired != 1) {
            throw new IllegalStateException(
                    "Generation is no longer published during retention"
            );
        }

        int deleted = jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET published_generation = NULL,
                    retention_status = 'DELETED',
                    lifecycle_status = 'DELETED',
                    deleted_at = clock_timestamp(),
                    claim_generation = NULL,
                    claim_id = NULL,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    lease_until = NULL,
                    last_error = NULL,
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE document_id = ?
                  AND published_generation = ?
                  AND claim_generation = ?
                  AND claim_id = ?
                  AND claimed_by = ?
                  AND lease_until > clock_timestamp()
                  AND retention_status = 'DELETING'
                """,
                claim.documentId(),
                claim.generation(),
                claim.generation(),
                claim.claimId(),
                claim.workerId()
        );
        if (deleted != 1) {
            throw new StaleClaimException();
        }

        return new RetentionCleanupResult(
                claim.documentId(),
                claim.generation(),
                deletedProjections,
                RetentionCleanupResult.Status.DELETED
        );
    }

    private ClaimFence lockFence(RetentionClaim claim) {
        return jdbcTemplate.query(
                """
                SELECT claim_generation,
                       claim_id,
                       claimed_by,
                       published_generation,
                       retention_status,
                       access_level,
                       lifecycle_policy,
                       lease_until > clock_timestamp() AS lease_valid
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new ClaimFence(
                        rs.getLong("claim_generation") == claim.generation()
                                && claim.claimId().equals(
                                        rs.getObject("claim_id", UUID.class)
                                )
                                && claim.workerId().equals(
                                        rs.getString("claimed_by")
                                )
                                && rs.getLong("published_generation")
                                        == claim.generation()
                                && "DELETE_PENDING".equals(
                                        rs.getString("retention_status")
                                )
                                && rs.getBoolean("lease_valid"),
                        rs.getLong("access_level"),
                        rs.getString("lifecycle_policy")
                ),
                claim.documentId()
        ).stream().findFirst().orElse(new ClaimFence(false, 0L, "TTL"));
    }

    private boolean finalFenceValid(RetentionClaim claim) {
        Boolean valid = jdbcTemplate.queryForObject(
                """
                SELECT claim_generation = ?
                   AND claim_id = ?
                   AND claimed_by = ?
                   AND lease_until > clock_timestamp()
                   AND retention_status = 'DELETING'
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                """,
                Boolean.class,
                claim.generation(),
                claim.claimId(),
                claim.workerId(),
                claim.documentId()
        );
        return Boolean.TRUE.equals(valid);
    }

    private void observe(RetentionCleanupResult result) {
        if (metrics != null) {
            metrics.retentionResult(
                    result.status().name(),
                    result.deletedChunks()
            );
            if (result.status() == RetentionCleanupResult.Status.STALE_CLAIM) {
                metrics.retentionStaleClaim();
            }
        }
        LOGGER.info(
                "retention_hot_purge event=result status={} generation={} deletedChunks={}",
                result.status(),
                result.generation(),
                result.deletedChunks()
        );
    }

    private RetentionCleanupResult stale(RetentionClaim claim) {
        return new RetentionCleanupResult(
                claim.documentId(),
                claim.generation(),
                0,
                RetentionCleanupResult.Status.STALE_CLAIM
        );
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        value = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record ClaimFence(
            boolean valid,
            long accessLevel,
            String lifecyclePolicy
    ) {
    }

    private record GenerationDescriptor(
            String profileId,
            String contentFingerprint,
            short physicalIdVersion,
            Integer chunkCount
    ) {
    }

    private static final class StaleClaimException extends RuntimeException {
    }
}
