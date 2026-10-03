package kz.alimbetov.akmai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.ReembeddingProperties;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.ingestion.GenerationVectorAssembler;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReembeddingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai").withUsername("akmai").withPassword("akmai");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static EmbeddingProfileRepository profiles;
    static EmbeddingProfileStorageManager storage;
    static PostgresSearchProjectionRepository projections;
    static DocumentIdentifierRepository identifiers;
    static ReferenceGraphRepository references;
    static VectorGenerationRepository manifests;
    static PostgresGenerationVectorRepository vectors;
    static EmbeddingProfile source;
    static EmbeddingProfile target;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(ds);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ObjectMapper mapper = new ObjectMapper();

        profiles = new EmbeddingProfileRepository(jdbc);
        storage = new EmbeddingProfileStorageManager(jdbc);
        projections = new PostgresSearchProjectionRepository(jdbc, mapper);
        identifiers = new DocumentIdentifierRepository(jdbc);
        references = new ReferenceGraphRepository(jdbc, new CrossReferenceExtractor());
        manifests = new VectorGenerationRepository(jdbc);
        vectors = new PostgresGenerationVectorRepository(jdbc, mapper, storage);

        source = profile("ep-source", "p_source", 3, "source");
        target = profile("ep-target", "p_target", 4, "target");
        storage.ensureStorage(source);
        storage.ensureStorage(target);
        profiles.save(source);
        profiles.save(target);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM akmai_vector.p_source");
        jdbc.update("DELETE FROM akmai_vector.p_target");
        jdbc.update("DELETE FROM knowledge_embedding_migration_document");
        jdbc.update("DELETE FROM knowledge_embedding_migration");
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET active_profile_id = ?,
                    migration_profile_id = NULL,
                    migration_status = 'IDLE',
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE singleton_id = 1
                """,
                source.profileId()
        );
    }

    @Test
    void successfulMigrationCutsOverWholeDocumentToDifferentDimensionProfile() {
        seedPublishedDocument("doc-1");

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList()))
                .thenReturn(List.of(new float[] {1f, 0f, 0f, 0f}));

        ReembeddingService service = service(model);
        ReembeddingService.MigrationResult result =
                service.migrateToConfiguredProfile();

        assertThat(result.migrated()).isTrue();
        assertThat(result.documents()).isEqualTo(1);
        assertThat(profiles.runtime().activeProfileId())
                .isEqualTo(target.profileId());
        assertThat(profiles.runtime().migrationStatus())
                .isEqualTo(EmbeddingRuntime.MigrationStatus.IDLE);

        Long published = jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-1'
                """,
                Long.class
        );
        assertThat(published).isEqualTo(2L);

        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-1' AND generation = 1
                """,
                String.class
        )).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-1' AND generation = 2
                """,
                String.class
        )).isEqualTo("PUBLISHED");

        assertThat(vectors.findIdsByGeneration(
                target,
                new GenerationIdentity("doc-1", 2L, 1L)
        )).hasSize(1);
        assertThat(projections.findGeneration("doc-1", 2L))
                .extracting(SearchProjection::chunkId)
                .containsExactly("chunk-1");
    }

    @Test
    void failedTargetEmbeddingPreservesSourcePublicationAndActiveProfile() {
        seedPublishedDocument("doc-1");

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList()))
                .thenThrow(new IllegalStateException("target embedding failed"));

        assertThatThrownBy(() -> service(model).migrateToConfiguredProfile())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target embedding failed");

        assertThat(profiles.runtime().activeProfileId())
                .isEqualTo(source.profileId());
        assertThat(profiles.runtime().migrationStatus())
                .isEqualTo(EmbeddingRuntime.MigrationStatus.IDLE);
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-1'
                """,
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-1' AND generation = 1
                """,
                String.class
        )).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_generation
                WHERE document_id = 'doc-1'
                  AND generation_kind = 'REEMBEDDING'
                  AND generation_status = 'FAILED'
                """,
                Integer.class
        )).isEqualTo(1);
    }

    private ReembeddingService service(EmbeddingModel model) {
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        when(resolver.configuredProfile()).thenReturn(target);
        EmbeddingProfileService profileService =
                new EmbeddingProfileService(resolver, profiles, storage);

        return new ReembeddingService(
                jdbc,
                tx,
                profileService,
                profiles,
                new GenerationEmbeddingService(model),
                projections,
                identifiers,
                references,
                manifests,
                vectors,
                new GenerationVectorAssembler(),
                new ReembeddingProperties(
                        false,
                        Duration.ofSeconds(2),
                        Duration.ofMillis(10)
                )
        );
    }

    private void seedPublishedDocument(String documentId) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, access_level
                ) VALUES (
                    ?, 'PERMANENT', 'READY',
                    1, 0, 0,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE',
                    1, 2, 1
                )
                """,
                documentId
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    cleanup_required, started_at, published_at, access_level
                ) VALUES (
                    ?, 1, 'PUBLISHED', 'INGESTION', ?,
                    'fp', 2, false,
                    clock_timestamp() - interval '1 hour',
                    clock_timestamp() - interval '30 minutes', 1
                )
                """,
                documentId,
                source.profileId()
        );
        projections.saveAll(List.of(new SearchProjection(
                "chunk-1",
                documentId,
                1L,
                null,
                0,
                "canonical text",
                "canonical embedding text",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", "reembedding-test"),
                2
        )));
    }

    private static EmbeddingProfile profile(
            String id,
            String table,
            int dimensions,
            String fingerprint
    ) {
        return new EmbeddingProfile(
                id,
                "test",
                "deterministic",
                dimensions,
                "COSINE_DISTANCE",
                "test",
                fingerprint,
                "akmai_vector",
                table,
                "NONE",
                (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );
    }
}
