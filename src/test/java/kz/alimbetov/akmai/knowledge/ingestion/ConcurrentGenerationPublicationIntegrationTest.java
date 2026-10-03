package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
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
class ConcurrentGenerationPublicationIntegrationTest {

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
    static DocumentGenerationRepository generations;
    static GenerationPublicationService publication;
    static EmbeddingProfile profile;

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

        profile = new EmbeddingProfile(
                "ep-concurrency",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "concurrency",
                "akmai_vector",
                "p_concurrency",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );
        EmbeddingProfileRepository profiles =
                new EmbeddingProfileRepository(jdbc);
        profiles.save(profile);
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET active_profile_id = ?, migration_status = 'IDLE'
                WHERE singleton_id = 1
                """,
                profile.profileId()
        );

        generations = new DocumentGenerationRepository(jdbc, tx);
        var projectionRepository =
                new PostgresSearchProjectionRepository(jdbc, objectMapper);
        var identifierRepository = new DocumentIdentifierRepository(jdbc);
        var manifests = new VectorGenerationRepository(jdbc);
        var references = new ReferenceGraphRepository(
                jdbc,
                new CrossReferenceExtractor()
        );
        var vectors = new PostgresGenerationVectorRepository(
                jdbc,
                objectMapper,
                new EmbeddingProfileStorageManager(jdbc)
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
                projectionRepository,
                identifierRepository,
                manifests,
                references,
                vectors,
                idempotency,
                resolver
        );
    }

    @BeforeEach
    void clean() {
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
    void newerPublishedGenerationCannotBeReplacedByOlderStagingGeneration() {
        long first = generations.allocate(
                "doc-race",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-1",
                1L
        );
        long second = generations.allocate(
                "doc-race",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-2",
                1L
        );

        var secondResult = publication.publish(
                "doc-race",
                second,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var firstResult = publication.publish(
                "doc-race",
                first,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(secondResult)
                .isEqualTo(GenerationPublicationService.PublicationResult.PUBLISHED);
        assertThat(firstResult)
                .isEqualTo(GenerationPublicationService.PublicationResult.SUPERSEDED);

        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-race'
                """,
                Long.class
        )).isEqualTo(second);

        assertThat(jdbc.queryForObject(
                """
                SELECT failure_code
                FROM knowledge_document_generation
                WHERE document_id = 'doc-race'
                  AND generation = ?
                """,
                String.class,
                first
        )).isEqualTo("SUPERSEDED");
    }

    @Test
    void accessLevelChangesOnlyWhenReplacementGenerationPublishes() {
        long first = generations.allocate(
                "doc-access-cutover",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-access-1",
                1L
        );
        publication.publish(
                "doc-access-cutover",
                first,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(accessLevel("doc-access-cutover")).isEqualTo(1L);

        long second = generations.allocate(
                "doc-access-cutover",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-access-2",
                2L
        );

        assertThat(accessLevel("doc-access-cutover"))
                .as("staging access must not affect published visibility")
                .isEqualTo(1L);

        publication.publish(
                "doc-access-cutover",
                second,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(accessLevel("doc-access-cutover")).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT access_level
                FROM knowledge_document_generation
                WHERE document_id = 'doc-access-cutover'
                  AND generation = ?
                """,
                Long.class,
                second
        )).isEqualTo(2L);
    }

    @Test
    void failedReplacementDoesNotChangePublishedAccessLevel() {
        long first = generations.allocate(
                "doc-access-failure",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-access-old",
                4L
        );
        publication.publish(
                "doc-access-failure",
                first,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        long failed = generations.allocate(
                "doc-access-failure",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-access-new",
                9L
        );
        generations.fail(
                "doc-access-failure",
                failed,
                "TEST_FAILURE",
                "replacement failed"
        );

        assertThat(accessLevel("doc-access-failure")).isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-access-failure'
                """,
                Long.class
        )).isEqualTo(first);
    }

    private long accessLevel(String documentId) {
        Long value = jdbc.queryForObject(
                """
                SELECT access_level
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                """,
                Long.class,
                documentId
        );
        if (value == null) {
            throw new AssertionError("missing lifecycle row");
        }
        return value;
    }

}
