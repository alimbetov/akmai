package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
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
class GenerationPublicationLifecycleIntegrationTest {

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
    static PostgresSearchProjectionRepository projections;
    static VectorGenerationRepository manifests;
    static EmbeddingProfile profile;
    static DocumentIdentifierRepository identifiers;
    static ReferenceGraphRepository references;
    static IngestionIdempotencyRepository idempotency;
    static PublicationOutcomeResolver resolver;

    ExecutorService executor;

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
        ObjectMapper mapper = new ObjectMapper();

        profile = new EmbeddingProfile(
                "ep-publication-lifecycle",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "publication-lifecycle",
                "akmai_vector",
                "p_publication_lifecycle",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-08T00:00:00Z")
        );
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

        generations = new DocumentGenerationRepository(jdbc, tx);
        projections = new PostgresSearchProjectionRepository(jdbc, mapper);
        identifiers = new DocumentIdentifierRepository(jdbc);
        manifests = new VectorGenerationRepository(jdbc);
        references = new ReferenceGraphRepository(
                jdbc,
                new CrossReferenceExtractor()
        );
        PostgresGenerationVectorRepository vectors =
                new PostgresGenerationVectorRepository(
                        jdbc,
                        mapper,
                        new EmbeddingProfileStorageManager(jdbc)
                );
        idempotency = new IngestionIdempotencyRepository(jdbc, tx, mapper);
        resolver = new PublicationOutcomeResolver(jdbc);

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
        executor = Executors.newFixedThreadPool(2);
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_ingestion_request");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentPublicationConvergesOnNewestGeneration() throws Exception {
        long first = allocate("doc-concurrent", "fp-1");
        long second = allocate("doc-concurrent", "fp-2");
        CountDownLatch start = new CountDownLatch(1);

        Future<GenerationPublicationService.PublicationResult> firstResult =
                executor.submit(() -> {
                    start.await();
                    return publishEmpty("doc-concurrent", first);
                });
        Future<GenerationPublicationService.PublicationResult> secondResult =
                executor.submit(() -> {
                    start.await();
                    return publishEmpty("doc-concurrent", second);
                });

        start.countDown();

        GenerationPublicationService.PublicationResult firstOutcome =
                firstResult.get();
        GenerationPublicationService.PublicationResult secondOutcome =
                secondResult.get();

        assertThat(secondOutcome)
                .isEqualTo(GenerationPublicationService.PublicationResult.PUBLISHED);
        assertThat(firstOutcome).isIn(
                GenerationPublicationService.PublicationResult.PUBLISHED,
                GenerationPublicationService.PublicationResult.SUPERSEDED
        );
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-concurrent'
                """,
                Long.class
        )).isEqualTo(second);
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-concurrent'
                  AND generation = ?
                """,
                String.class,
                second
        )).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-concurrent'
                  AND generation = ?
                """,
                String.class,
                first
        )).isIn("RETIRING", "FAILED");
    }

    @Test
    void lateVectorFailureRollsBackStagedProjectionAndManifest() {
        long previous = allocate("doc-rollback", "fp-old");
        publishEmpty("doc-rollback", previous);
        long candidate = allocate("doc-rollback", "fp-new");
        SearchProjection projection = projection(
                "doc-rollback",
                candidate,
                "candidate-chunk"
        );

        PostgresGenerationVectorRepository failingVectors =
                mock(PostgresGenerationVectorRepository.class);
        IllegalStateException vectorFailure =
                new IllegalStateException("vector storage unavailable");
        doThrow(vectorFailure).when(failingVectors).insertAll(
                any(EmbeddingProfile.class),
                any(GenerationIdentity.class),
                any()
        );

        GenerationPublicationService failingPublication =
                new GenerationPublicationService(
                        jdbc,
                        tx,
                        projections,
                        identifiers,
                        manifests,
                        references,
                        failingVectors,
                        idempotency,
                        resolver
                );

        assertThatThrownBy(() -> failingPublication.publish(
                "doc-rollback",
                candidate,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(projection),
                List.of(),
                List.of(),
                List.of(new PostgresGenerationVectorRepository.VectorRow(
                        UUID.randomUUID().toString(),
                        "candidate-chunk",
                        "en",
                        "candidate text",
                        Map.of(
                                "akmaiDocumentId", "doc-rollback",
                                "akmaiGeneration", candidate,
                                "akmaiChunkId", "candidate-chunk",
                                "language", "en"
                        ),
                        new float[] {1f, 0f, 0f}
                ))
        )).isSameAs(vectorFailure);

        assertThat(projections.findGeneration("doc-rollback", candidate))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_vector_generation
                WHERE document_id = 'doc-rollback'
                  AND generation = ?
                """,
                Integer.class,
                candidate
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-rollback'
                  AND generation = ?
                """,
                String.class,
                candidate
        )).isEqualTo("STAGING");
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-rollback'
                  AND generation = ?
                """,
                String.class,
                previous
        )).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-rollback'
                """,
                Long.class
        )).isEqualTo(previous);
    }

    private long allocate(String documentId, String fingerprint) {
        return generations.allocate(
                documentId,
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                fingerprint,
                1L
        );
    }

    private GenerationPublicationService.PublicationResult publishEmpty(
            String documentId,
            long generation
    ) {
        return publication.publish(
                documentId,
                generation,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private SearchProjection projection(
            String documentId,
            long generation,
            String chunkId
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                generation,
                1L,
                null,
                0,
                "candidate text",
                "candidate text",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", "publication-lifecycle-test"),
                2
        );
    }
}
