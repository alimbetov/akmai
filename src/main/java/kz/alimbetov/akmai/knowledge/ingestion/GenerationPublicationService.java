package kz.alimbetov.akmai.knowledge.ingestion;

import java.time.Instant;
import java.util.List;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationPublicationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SearchProjectionRepository projectionRepository;
    private final DocumentIdentifierRepository identifierRepository;
    private final VectorGenerationRepository vectorGenerationRepository;
    private final ReferenceGraphRepository referenceGraphRepository;
    private final PostgresGenerationVectorRepository vectorRepository;
    private final IngestionIdempotencyRepository idempotencyRepository;
    private final PublicationOutcomeResolver outcomeResolver;

    public GenerationPublicationService(
            JdbcTemplate jdbcTemplate,
            @Qualifier("publicationTransactionTemplate")
            TransactionTemplate transactionTemplate,
            SearchProjectionRepository projectionRepository,
            DocumentIdentifierRepository identifierRepository,
            VectorGenerationRepository vectorGenerationRepository,
            ReferenceGraphRepository referenceGraphRepository,
            PostgresGenerationVectorRepository vectorRepository,
            IngestionIdempotencyRepository idempotencyRepository,
            PublicationOutcomeResolver outcomeResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.projectionRepository = projectionRepository;
        this.identifierRepository = identifierRepository;
        this.vectorGenerationRepository = vectorGenerationRepository;
        this.referenceGraphRepository = referenceGraphRepository;
        this.vectorRepository = vectorRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outcomeResolver = outcomeResolver;
    }

    public PublicationResult publish(
            String documentId,
            long generation,
            RetentionPolicy policy,
            Instant expiresAt,
            EmbeddingProfile profile,
            List<SearchProjection> projections,
            List<DocumentIdentifier> identifiers,
            List<VectorGenerationEntry> manifest,
            List<VectorRow> vectors
    ) {
        return publish(
                documentId,
                generation,
                policy,
                expiresAt,
                profile,
                projections,
                identifiers,
                manifest,
                vectors,
                null,
                null
        );
    }

    public PublicationResult publish(
            String documentId,
            long generation,
            RetentionPolicy policy,
            Instant expiresAt,
            EmbeddingProfile profile,
            List<SearchProjection> projections,
            List<DocumentIdentifier> identifiers,
            List<VectorGenerationEntry> manifest,
            List<VectorRow> vectors,
            IngestionIdempotencyContext idempotency,
            KnowledgeIngestionResponse response
    ) {
        try {
            PublicationResult result = transactionTemplate.execute(
                    status -> publishInTransaction(
                            documentId,
                            generation,
                            policy,
                            expiresAt,
                            profile,
                            projections,
                            identifiers,
                            manifest,
                            vectors,
                            idempotency,
                            response
                    )
            );
            if (result == null) {
                throw new IllegalStateException(
                        "Publication transaction returned no result"
                );
            }
            return result;
        } catch (RuntimeException exception) {
            PublicationOutcomeResolver.Outcome outcome =
                    outcomeResolver.resolve(
                            documentId,
                            generation,
                            idempotency
                    );
            if (outcome == PublicationOutcomeResolver.Outcome.COMMITTED) {
                return PublicationResult.PUBLISHED;
            }
            if (outcome == PublicationOutcomeResolver.Outcome.SUPERSEDED) {
                return PublicationResult.SUPERSEDED;
            }
            throw exception;
        }
    }

    private PublicationResult publishInTransaction(
            String documentId,
            long generation,
            RetentionPolicy policy,
            Instant expiresAt,
            EmbeddingProfile profile,
            List<SearchProjection> projections,
            List<DocumentIdentifier> identifiers,
            List<VectorGenerationEntry> manifest,
            List<VectorRow> vectors,
            IngestionIdempotencyContext idempotency,
            KnowledgeIngestionResponse response
    ) {
        GenerationLock generationState = jdbcTemplate.query(
                """
                SELECT generation_status, embedding_profile_id
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new GenerationLock(
                        rs.getString("generation_status"),
                        rs.getString("embedding_profile_id")
                ),
                documentId,
                generation
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Generation does not exist"));

        if (!"STAGING".equals(generationState.status())) {
            if ("PUBLISHED".equals(generationState.status())) {
                return PublicationResult.ALREADY_PUBLISHED;
            }
            throw new IllegalStateException(
                    "Generation is not publishable: " + generationState.status()
            );
        }
        if (!profile.profileId().equals(generationState.embeddingProfileId())) {
            throw new IllegalStateException(
                    "Embedding profile changed for generation"
            );
        }

        String activeProfile = jdbcTemplate.queryForObject(
                """
                SELECT active_profile_id
                FROM knowledge_embedding_runtime
                WHERE singleton_id = 1
                FOR UPDATE
                """,
                String.class
        );
        if (!profile.profileId().equals(activeProfile)) {
            throw new IllegalStateException(
                    "Generation profile is not the corpus active profile"
            );
        }

        Long previous = jdbcTemplate.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                FOR UPDATE
                """,
                Long.class,
                documentId
        );

        if (previous != null && previous > generation) {
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation
                    SET generation_status = 'FAILED',
                        failure_code = 'SUPERSEDED',
                        last_error = 'A newer generation was already published',
                        failed_at = clock_timestamp()
                    WHERE document_id = ?
                      AND generation = ?
                      AND generation_status = 'STAGING'
                    """,
                    documentId,
                    generation
            );
            return PublicationResult.SUPERSEDED;
        }

        projectionRepository.saveAll(projections);
        identifierRepository.saveAll(identifiers);
        referenceGraphRepository.saveAll(projections);
        vectorGenerationRepository.save(
                documentId,
                generation,
                profile.profileId(),
                VectorIdentity.VERSION,
                manifest
        );
        vectorRepository.insertAll(profile, vectors);

        if (previous != null && previous != generation) {
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation
                    SET generation_status = 'RETIRED',
                        retired_at = clock_timestamp()
                    WHERE document_id = ?
                      AND generation = ?
                      AND generation_status = 'PUBLISHED'
                    """,
                    documentId,
                    previous
            );
        }

        int published = jdbcTemplate.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'PUBLISHED',
                    published_at = clock_timestamp(),
                    failure_code = NULL,
                    last_error = NULL
                WHERE document_id = ?
                  AND generation = ?
                  AND generation_status = 'STAGING'
                """,
                documentId,
                generation
        );
        if (published != 1) {
            throw new IllegalStateException(
                    "Generation publication fence failed"
            );
        }

        int lifecycle = jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET published_generation = ?,
                    generation = ?,
                    lifecycle_status = 'READY',
                    retention_status = 'ACTIVE',
                    lifecycle_policy = ?,
                    expires_at = ?,
                    ingestion_started_at = NULL,
                    attempt_count = 0,
                    last_error = NULL,
                    claim_generation = NULL,
                    claim_id = NULL,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    lease_until = NULL,
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE document_id = ?
                """,
                generation,
                generation,
                policy.name(),
                timestamp(expiresAt),
                documentId
        );
        if (lifecycle != 1) {
            throw new IllegalStateException(
                    "Lifecycle publication fence failed"
            );
        }

        if (idempotency != null) {
            if (response == null) {
                throw new IllegalArgumentException(
                        "Idempotent publication requires a response"
                );
            }
            idempotencyRepository.completeInCurrentTransaction(
                    idempotency,
                    response
            );
        }

        return PublicationResult.PUBLISHED;
    }

    private java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private record GenerationLock(
            String status,
            String embeddingProfileId
    ) {
    }

    public enum PublicationResult {
        PUBLISHED,
        ALREADY_PUBLISHED,
        SUPERSEDED
    }
}
