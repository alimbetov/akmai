package kz.alimbetov.akmai.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.GenerationPublicationService;
import kz.alimbetov.akmai.knowledge.ingestion.PublicationOutcomeResolver;
import kz.alimbetov.akmai.knowledge.ingestion.VectorIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
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
class PostgresVectorPublicationIntegrationTest {

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
    static TransactionTemplate tx;
    static EmbeddingProfile profile;
    static EmbeddingProfileStorageManager storage;
    static DocumentGenerationRepository generations;
    static GenerationPublicationService publication;

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
        tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );

        ObjectMapper objectMapper = new ObjectMapper();
        storage = new EmbeddingProfileStorageManager(jdbc);
        EmbeddingProfileRepository profiles =
                new EmbeddingProfileRepository(jdbc);
        profile = new EmbeddingProfile(
                "ep_e2e",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "e2e",
                "akmai_vector",
                "p_e2e",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );
        storage.ensureStorage(profile);
        profiles.save(profile);
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET active_profile_id = ?,
                    migration_status = 'IDLE',
                    migration_profile_id = NULL
                WHERE singleton_id = 1
                """,
                profile.profileId()
        );

        generations = new DocumentGenerationRepository(jdbc, tx);
        var projections =
                new PostgresSearchProjectionRepository(jdbc, objectMapper);
        var identifiers = new DocumentIdentifierRepository(jdbc);
        var manifests = new VectorGenerationRepository(jdbc);
        var references = new ReferenceGraphRepository(
                jdbc,
                new CrossReferenceExtractor()
        );
        var vectors = new PostgresGenerationVectorRepository(
                jdbc,
                objectMapper,
                storage
        );
        var idempotency = new IngestionIdempotencyRepository(
                jdbc,
                tx,
                objectMapper
        );
        var resolver = new PublicationOutcomeResolver(jdbc);
        publication = new GenerationPublicationService(
                jdbc,
                tx,
                projections,
                identifiers,
                manifests,
                references,
                vectors,
                idempotency,
                resolver
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM akmai_vector.p_e2e");
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_ingestion_request");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void publicationCommitsVectorManifestProjectionAndPointerTogether() {
        long generation = generations.allocate(
                "doc-1",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp"
        );
        SearchProjection projection = projection(generation, "chunk-1");
        String vectorId = VectorIdentity.physicalId(
                "doc-1",
                generation,
                "chunk-1"
        );

        publication.publish(
                "doc-1",
                generation,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(projection),
                List.of(),
                List.of(new VectorGenerationEntry(vectorId, "chunk-1")),
                List.of(vector(vectorId, generation, "chunk-1"))
        );

        assertThat(count("akmai_vector.p_e2e")).isEqualTo(1);
        assertThat(count("knowledge_search_projection")).isEqualTo(1);
        assertThat(count("knowledge_document_vector_generation")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-1'
                """,
                Long.class
        )).isEqualTo(generation);
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-1' AND generation = ?
                """,
                String.class,
                generation
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void laterVectorFailureRollsBackEntirePublication() {
        long generation = generations.allocate(
                "doc-2",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp"
        );
        SearchProjection projection = projection(generation, "chunk-2");
        String vectorId = VectorIdentity.physicalId(
                "doc-2",
                generation,
                "chunk-2"
        );

        assertThatThrownBy(() -> publication.publish(
                "doc-2",
                generation,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(projection),
                List.of(),
                List.of(new VectorGenerationEntry(vectorId, "chunk-2")),
                List.of(new PostgresGenerationVectorRepository.VectorRow(
                        vectorId,
                        projection.embeddingText(),
                        metadata(generation, "doc-2", "chunk-2"),
                        new float[] {1f, 2f}
                ))
        )).isInstanceOf(RuntimeException.class);

        assertThat(count("akmai_vector.p_e2e")).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_search_projection
                WHERE document_id = 'doc-2' AND generation = ?
                """,
                Integer.class,
                generation
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_vector_generation
                WHERE document_id = 'doc-2' AND generation = ?
                """,
                Integer.class,
                generation
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-2'
                """,
                Long.class
        )).isNull();
    }

    private SearchProjection projection(long generation, String chunkId) {
        return new SearchProjection(
                chunkId,
                chunkId.startsWith("chunk-1") ? "doc-1" : "doc-2",
                generation,
                null,
                0,
                "canonical text",
                "embedding text",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", "e2e"),
                2
        );
    }

    private PostgresGenerationVectorRepository.VectorRow vector(
            String id,
            long generation,
            String chunkId
    ) {
        return new PostgresGenerationVectorRepository.VectorRow(
                id,
                "embedding text",
                metadata(generation, "doc-1", chunkId),
                new float[] {1f, 0f, 0f}
        );
    }

    private Map<String, Object> metadata(
            long generation,
            String documentId,
            String chunkId
    ) {
        return Map.of(
                "akmaiMetadataVersion", 2,
                "akmaiDocumentId", documentId,
                "akmaiGeneration", generation,
                "akmaiEmbeddingProfileId", profile.profileId(),
                "akmaiChunkId", chunkId
        );
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table,
                Integer.class
        );
    }
}
