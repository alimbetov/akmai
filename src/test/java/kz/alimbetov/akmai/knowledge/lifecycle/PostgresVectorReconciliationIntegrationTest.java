package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.knowledge.audit.AuditEventRepository;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.ingestion.VectorIdentity;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgresVectorReconciliationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static EmbeddingProfile profile;
    static EmbeddingProfileStorageManager storage;
    static PostgresGenerationVectorRepository vectors;
    static VectorGenerationRepository manifests;
    static PostgresSearchProjectionRepository projections;
    static GenerationReconciliationService reconciliation;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(
                "classpath:db/changelog/db.changelog-master.yaml"
        );
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        ObjectMapper mapper = new ObjectMapper();

        storage = new EmbeddingProfileStorageManager(jdbc);
        profile = new EmbeddingProfile(
                "ep-reconcile",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "reconcile",
                "akmai_vector",
                "p_reconcile",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );
        storage.ensureStorage(profile);

        EmbeddingProfileRepository profileRepository =
                new EmbeddingProfileRepository(jdbc);
        profileRepository.save(profile);
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET active_profile_id = ?, migration_status = 'IDLE'
                WHERE singleton_id = 1
                """,
                profile.profileId()
        );

        projections = new PostgresSearchProjectionRepository(jdbc, mapper);
        DocumentIdentifierRepository identifiers =
                new DocumentIdentifierRepository(jdbc);
        manifests = new VectorGenerationRepository(jdbc);
        ReferenceGraphRepository references =
                new ReferenceGraphRepository(jdbc, new CrossReferenceExtractor());
        vectors = new PostgresGenerationVectorRepository(jdbc, mapper, storage);

        GenerationRepairService repairService =
                new GenerationRepairService(
                        vectors,
                        references,
                        identifiers,
                        projections,
                        manifests
                );

        reconciliation = new GenerationReconciliationService(
                jdbc,
                tx,
                vectors,
                profileRepository,
                repairService,
                new ReconciliationProperties(
                        true,
                        10,
                        1,
                        Duration.ZERO,
                        Duration.ofMinutes(5)
                ),
                new AuditEventRepository(jdbc, mapper)
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_audit_event");
        jdbc.update("DELETE FROM knowledge_retired_generation");
        jdbc.update("DELETE FROM akmai_vector.p_reconcile");
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void retiredGenerationIsFullyCleanedWithoutTouchingPublishedGeneration() {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, access_level
                ) VALUES (
                    'doc-1', 'PERMANENT', 'READY',
                    2, 0, 0,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE',
                    2, 3, 1
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    cleanup_required, started_at, retired_at, access_level
                ) VALUES (
                    'doc-1', 1, 'RETIRED',
                    'INGESTION', ?, 'fp-1', 2,
                    true, clock_timestamp() - interval '1 hour',
                    clock_timestamp() - interval '30 minutes', 1
                )
                """,
                profile.profileId()
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    cleanup_required, started_at, published_at, access_level
                ) VALUES (
                    'doc-1', 2, 'PUBLISHED',
                    'INGESTION', ?, 'fp-2', 2,
                    false, clock_timestamp() - interval '20 minutes',
                    clock_timestamp() - interval '10 minutes', 1
                )
                """,
                profile.profileId()
        );

        SearchProjection oldProjection = projection(1L, "old-chunk", "old text");
        SearchProjection currentProjection = projection(2L, "new-chunk", "new text");
        projections.saveAll(List.of(oldProjection, currentProjection));

        String oldVectorId = VectorIdentity.physicalId(
                "doc-1", 1L, "old-chunk"
        );
        String newVectorId = VectorIdentity.physicalId(
                "doc-1", 2L, "new-chunk"
        );
        manifests.save(
                "doc-1",
                1L,
                profile.profileId(),
                VectorIdentity.VERSION,
                List.of(new VectorGenerationRepository.VectorGenerationEntry(
                        oldVectorId,
                        "old-chunk"
                ))
        );
        manifests.save(
                "doc-1",
                2L,
                profile.profileId(),
                VectorIdentity.VERSION,
                List.of(new VectorGenerationRepository.VectorGenerationEntry(
                        newVectorId,
                        "new-chunk"
                ))
        );
        vectors.insertAll(
                profile,
                List.of(
                        row(oldVectorId, 1L, "old-chunk"),
                        row(newVectorId, 2L, "new-chunk")
                )
        );

        GenerationIdentity oldIdentity =
                new GenerationIdentity("doc-1", 1L, 1L);
        insertRetiredTombstone(
                "doc-1",
                1L,
                profile.profileId(),
                1,
                1
        );
        assertThat(vectors.countGeneration(profile, oldIdentity))
                .isEqualTo(1);

        assertThat(reconciliation.reconcileBatch()).isEqualTo(1);

        assertThat(vectors.countGeneration(profile, oldIdentity)).isZero();
        assertThat(vectors.countExisting(
                profile,
                new GenerationIdentity("doc-1", 2L, 1L),
                List.of(newVectorId)
        )).isEqualTo(1);

        assertThat(projections.findGeneration("doc-1", 1L)).isEmpty();
        assertThat(projections.findGeneration("doc-1", 2L))
                .extracting(SearchProjection::chunkId)
                .containsExactly("new-chunk");

        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-1'
                  AND generation = 1
                """,
                String.class
        )).isEqualTo("CLEANED");

        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-1'
                """,
                Long.class
        )).isEqualTo(2L);
    }

    @Test
    void missingManifestUsesVerifiedGenerationMetadataBeforeMarkingCleaned() {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, access_level
                ) VALUES (
                    'doc-missing', 'PERMANENT', 'READY',
                    2, 0, 0,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE',
                    2, 3, 1
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    cleanup_required, started_at, retired_at, access_level
                ) VALUES (
                    'doc-missing', 1, 'RETIRED',
                    'INGESTION', ?, 'fp-old', 2,
                    true, clock_timestamp() - interval '1 hour',
                    clock_timestamp() - interval '30 minutes', 1
                )
                """,
                profile.profileId()
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    cleanup_required, started_at, published_at, access_level
                ) VALUES (
                    'doc-missing', 2, 'PUBLISHED',
                    'INGESTION', ?, 'fp-new', 2,
                    false, clock_timestamp() - interval '20 minutes',
                    clock_timestamp() - interval '10 minutes', 1
                )
                """,
                profile.profileId()
        );

        String oldVectorId = VectorIdentity.physicalId(
                "doc-missing", 1L, "old-missing"
        );
        vectors.insertAll(
                profile,
                List.of(new PostgresGenerationVectorRepository.VectorRow(
                        oldVectorId,
                        "embedding",
                        Map.of(
                                "akmaiMetadataVersion", 2,
                                "akmaiDocumentId", "doc-missing",
                                "akmaiGeneration", 1L,
                                "akmaiEmbeddingProfileId", profile.profileId(),
                                "akmaiChunkId", "old-missing",
                                "language", "en"
                        ),
                        new float[] {1f, 0f, 0f}
                ))
        );

        GenerationIdentity oldIdentity =
                new GenerationIdentity("doc-missing", 1L, 1L);
        insertRetiredTombstone(
                "doc-missing",
                1L,
                profile.profileId(),
                0,
                1
        );

        assertThat(manifests.findVectorIds("doc-missing", 1L)).isEmpty();
        assertThat(reconciliation.reconcileBatch()).isEqualTo(1);
        assertThat(vectors.countGeneration(profile, oldIdentity)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-missing' AND generation = 1
                """,
                String.class
        )).isEqualTo("CLEANED");
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-missing'
                """,
                Long.class
        )).isEqualTo(2L);
    }

    private void insertRetiredTombstone(
            String documentId,
            long generation,
            String profileId,
            int projectionCount,
            int vectorCount
    ) {
        jdbc.update(
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
                    retired_at,
                    purge_after,
                    cleanup_status
                )
                SELECT g.document_id,
                       g.generation,
                       g.access_level,
                       ?,
                       ?,
                       ?,
                       g.content_fingerprint,
                       g.physical_id_version,
                       l.lifecycle_policy,
                       COALESCE(g.retired_at, clock_timestamp())
                           - interval '1 minute',
                       COALESCE(g.retired_at, clock_timestamp()),
                       clock_timestamp() + interval '7 days',
                       'PURGED'
                FROM knowledge_document_generation g
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = g.document_id
                WHERE g.document_id = ?
                  AND g.generation = ?
                """,
                profileId,
                projectionCount,
                vectorCount,
                documentId,
                generation
        );
    }

    private SearchProjection projection(
            long generation,
            String chunkId,
            String text
    ) {
        return new SearchProjection(
                chunkId,
                "doc-1",
                generation,
                null,
                (int) generation,
                text,
                text,
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", "reconciliation-test"),
                2
        );
    }

    private PostgresGenerationVectorRepository.VectorRow row(
            String vectorId,
            long generation,
            String chunkId
    ) {
        return new PostgresGenerationVectorRepository.VectorRow(
                vectorId,
                "embedding",
                Map.of(
                        "akmaiMetadataVersion", 2,
                        "akmaiDocumentId", "doc-1",
                        "akmaiGeneration", generation,
                        "akmaiEmbeddingProfileId", profile.profileId(),
                        "akmaiChunkId", chunkId,
                        "language", "en"
                ),
                new float[] {1f, 0f, 0f}
        );
    }
}
