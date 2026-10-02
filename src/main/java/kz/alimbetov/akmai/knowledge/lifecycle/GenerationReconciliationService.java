package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Duration;
import java.util.List;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationReconciliationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final VectorGenerationRepository manifests;
    private final PostgresGenerationVectorRepository vectors;
    private final EmbeddingProfileRepository profiles;
    private final SearchProjectionRepository projections;
    private final DocumentIdentifierRepository identifiers;
    private final ReferenceGraphRepository references;
    private final ReconciliationProperties properties;

    public GenerationReconciliationService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            VectorGenerationRepository manifests,
            PostgresGenerationVectorRepository vectors,
            EmbeddingProfileRepository profiles,
            SearchProjectionRepository projections,
            DocumentIdentifierRepository identifiers,
            ReferenceGraphRepository references,
            ReconciliationProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.manifests = manifests;
        this.vectors = vectors;
        this.profiles = profiles;
        this.projections = projections;
        this.identifiers = identifiers;
        this.references = references;
        this.properties = properties;
    }

    public int reconcileBatch() {
        if (!properties.enabled()) {
            return 0;
        }
        List<GenerationKey> candidates = jdbcTemplate.query(
                """
                SELECT g.document_id, g.generation
                FROM knowledge_document_generation g
                WHERE g.generation_status IN ('RETIRED', 'FAILED')
                  AND g.started_at <
                      clock_timestamp() - (? * interval '1 millisecond')
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
                        rs.getLong("generation")
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
        return cleaned;
    }

    private boolean reconcileOne(GenerationKey key) {
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

        Integer published = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND published_generation = ?
                """,
                Integer.class,
                key.documentId(),
                key.generation()
        );
        if (published != null && published > 0) {
            return false;
        }

        List<String> vectorIds = manifests.findVectorIds(
                key.documentId(),
                key.generation()
        );

        if (row.profileId() != null) {
            EmbeddingProfile profile = profiles.findById(row.profileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing embedding profile " + row.profileId()
                    ));
            if (vectorIds.isEmpty()) {
                vectorIds = vectors.findIdsByGenerationMetadata(
                        profile,
                        key.documentId(),
                        key.generation()
                );
            }
            vectors.deleteIds(profile, vectorIds);
            if (vectors.countExisting(profile, vectorIds) != 0) {
                throw new IllegalStateException(
                        "Generation vectors remain after reconciliation"
                );
            }
        } else if (hasRelationalState(key)) {
            queueLegacyReconciliation(key);
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation
                    SET cleanup_required = true
                    WHERE document_id = ?
                      AND generation = ?
                    """,
                    key.documentId(),
                    key.generation()
            );
            return false;
        }

        references.deleteGeneration(key.documentId(), key.generation());
        identifiers.deleteGeneration(key.documentId(), key.generation());
        projections.deleteGeneration(key.documentId(), key.generation());
        manifests.deleteGeneration(key.documentId(), key.generation());

        return jdbcTemplate.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'CLEANED',
                    cleaned_at = clock_timestamp(),
                    cleanup_required = false
                WHERE document_id = ?
                  AND generation = ?
                  AND generation_status IN ('RETIRED', 'FAILED')
                """,
                key.documentId(),
                key.generation()
        ) == 1;
    }

    private boolean hasRelationalState(GenerationKey key) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT
                    (SELECT count(*) FROM knowledge_search_projection
                      WHERE document_id = ? AND generation = ?)
                  + (SELECT count(*) FROM document_identifier
                      WHERE document_id = ? AND generation = ?)
                  + (SELECT count(*) FROM knowledge_document_vector_generation
                      WHERE document_id = ? AND generation = ?)
                  + (SELECT count(*) FROM knowledge_reference_edge
                      WHERE document_id = ? AND generation = ?)
                  + (SELECT count(*) FROM knowledge_reference_target
                      WHERE document_id = ? AND generation = ?)
                """,
                Integer.class,
                key.documentId(),
                key.generation(),
                key.documentId(),
                key.generation(),
                key.documentId(),
                key.generation(),
                key.documentId(),
                key.generation(),
                key.documentId(),
                key.generation()
        );
        return count != null && count > 0;
    }

    private void queueLegacyReconciliation(GenerationKey key) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_legacy_reconciliation (
                    entity_type, entity_key, payload, reason
                )
                SELECT 'GENERATION',
                       ?,
                       jsonb_build_object(
                           'documentId', ?,
                           'generation', ?
                       ),
                       'GENERATION_WITHOUT_EMBEDDING_PROFILE'
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM knowledge_legacy_reconciliation
                    WHERE entity_type = 'GENERATION'
                      AND entity_key = ?
                      AND resolved_at IS NULL
                )
                """,
                key.documentId() + ":" + key.generation(),
                key.documentId(),
                key.generation(),
                key.documentId() + ":" + key.generation()
        );
    }

    private record GenerationKey(String documentId, long generation) {
    }

    private record GenerationRow(String status, String profileId) {
    }
}
