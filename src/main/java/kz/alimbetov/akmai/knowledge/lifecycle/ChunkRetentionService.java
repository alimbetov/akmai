package kz.alimbetov.akmai.knowledge.lifecycle;

import java.util.List;
import java.util.UUID;
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
            EmbeddingProfileRepository profileRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.claimRepository = claimRepository;
        this.projectionRepository = projectionRepository;
        this.identifierRepository = identifierRepository;
        this.vectorGenerationRepository = vectorGenerationRepository;
        this.vectorRepository = vectorRepository;
        this.profileRepository = profileRepository;
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

        String profileId = jdbcTemplate.queryForObject(
                """
                SELECT embedding_profile_id
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                """,
                String.class,
                claim.documentId(),
                claim.generation()
        );
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalStateException(
                    "Generation has no verifiable embedding profile"
            );
        }
        EmbeddingProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding profile is missing: " + profileId
                ));

        List<String> vectorIds =
                vectorGenerationRepository.findVectorIds(identity);
        if (vectorIds.isEmpty()) {
            throw new IllegalStateException(
                    "Vector manifest missing; reconciliation is required"
            );
        }

        Integer chunkCount = jdbcTemplate.queryForObject(
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

        int archivedVectors = vectorRepository.archiveGeneration(
                profile,
                identity
        );
        if (archivedVectors != vectorIds.size()) {
            throw new IllegalStateException(
                    "Vector archive count does not match generation manifest"
            );
        }
        if (vectorRepository.countGeneration(
                profile,
                identity,
                RetrievalStorageState.ACTIVE
        ) != 0) {
            throw new IllegalStateException(
                    "Active vectors remain after generation archive"
            );
        }

        int archivedProjections =
                projectionRepository.archiveGeneration(identity);
        if (chunkCount != null
                && archivedProjections != chunkCount) {
            throw new IllegalStateException(
                    "Projection archive count does not match generation"
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

        if (!finalFenceValid(claim)) {
            throw new StaleClaimException();
        }

        jdbcTemplate.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRED',
                    retired_at = COALESCE(retired_at, clock_timestamp()),
                    cleanup_required = true
                WHERE document_id = ?
                  AND generation = ?
                  AND generation_status IN ('PUBLISHED', 'RETIRED')
                """,
                claim.documentId(),
                claim.generation()
        );

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
                chunkCount == null ? 0 : chunkCount,
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
                        rs.getLong("access_level")
                ),
                claim.documentId()
        ).stream().findFirst().orElse(new ClaimFence(false, 0L));
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
                "retention_archive event=result status={} generation={} archivedChunks={}",
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
            long accessLevel
    ) {
    }

    private static final class StaleClaimException extends RuntimeException {
    }
}
