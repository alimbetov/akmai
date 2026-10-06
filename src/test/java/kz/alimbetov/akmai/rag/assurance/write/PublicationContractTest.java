package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.GenerationPublicationService;
import kz.alimbetov.akmai.knowledge.ingestion.PublicationOutcomeResolver;
import kz.alimbetov.akmai.knowledge.ingestion.VectorIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
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
class PublicationContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("akmai")
            .withUsername("akmai")
            .withPassword("akmai");

    private static JdbcTemplate jdbc;
    private static EmbeddingProfile activeProfile;
    private static EmbeddingProfile incompatibleProfile;
    private static DocumentGenerationRepository generations;
    private static PostgresSearchProjectionRepository projections;
    private static GenerationPublicationService publication;

    @BeforeAll
    static void migrateAndConfigureProfiles() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        ObjectMapper objectMapper = new ObjectMapper();
        EmbeddingProfileStorageManager storage = new EmbeddingProfileStorageManager(jdbc);
        EmbeddingProfileRepository profileRepository = new EmbeddingProfileRepository(jdbc);

        activeProfile = profile("ep_contract_active", "p_contract_active");
        incompatibleProfile = profile("ep_contract_other", "p_contract_other");
        storage.ensureStorage(activeProfile);
        storage.ensureStorage(incompatibleProfile);
        profileRepository.save(activeProfile);
        profileRepository.save(incompatibleProfile);

        generations = new DocumentGenerationRepository(jdbc, tx);
        projections = new PostgresSearchProjectionRepository(jdbc, objectMapper);
        var identifiers = new DocumentIdentifierRepository(jdbc);
        var manifests = new VectorGenerationRepository(jdbc);
        var references = new ReferenceGraphRepository(jdbc, new CrossReferenceExtractor());
        var vectors = new PostgresGenerationVectorRepository(jdbc, objectMapper, storage);
        var idempotency = new IngestionIdempotencyRepository(jdbc, tx, objectMapper);
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
        jdbc.update("DELETE FROM akmai_vector.p_contract_active");
        jdbc.update("DELETE FROM akmai_vector.p_contract_other");
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_ingestion_request");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET active_profile_id = ?,
                    migration_profile_id = NULL,
                    migration_status = 'IDLE'
                WHERE singleton_id = 1
                """,
                activeProfile.profileId()
        );
    }

    @Test
    void stagedGenerationIsInvisibleUntilPublication() {
        String documentId = "w09-document-001";
        long publishedGeneration = generations.allocate(
                documentId,
                RetentionPolicy.PERMANENT,
                null,
                activeProfile.profileId(),
                "w09-fingerprint-1",
                1L
        );
        publish(
                documentId,
                publishedGeneration,
                "published-chunk",
                activeProfile
        );

        long stagedGeneration = generations.allocate(
                documentId,
                RetentionPolicy.PERMANENT,
                null,
                activeProfile.profileId(),
                "w09-fingerprint-2",
                1L
        );
        SearchProjection staged = projection(
                documentId,
                stagedGeneration,
                "staged-chunk"
        );
        projections.saveAll(
                new GenerationIdentity(documentId, stagedGeneration, 1L),
                List.of(staged)
        );

        var visible = projections.findByDocumentAndChunkIds(
                documentId,
                List.of("published-chunk", "staged-chunk"),
                Set.of(1L)
        );

        assertDoesNotThrow(() -> RagAssertions.projections(visible)
                .forFixture("w09-staged-generation-isolation")
                .containsPublishedGenerationOnly(publishedGeneration)
                .hasChunkIds(Set.of("published-chunk")));
    }

    @Test
    void incompatibleEmbeddingProfileCannotReplacePublishedGeneration() {
        String documentId = "w10-document-001";
        long publishedGeneration = generations.allocate(
                documentId,
                RetentionPolicy.PERMANENT,
                null,
                activeProfile.profileId(),
                "w10-fingerprint-1",
                1L
        );
        publish(
                documentId,
                publishedGeneration,
                "active-profile-chunk",
                activeProfile
        );

        long incompatibleGeneration = generations.allocate(
                documentId,
                RetentionPolicy.PERMANENT,
                null,
                incompatibleProfile.profileId(),
                "w10-fingerprint-2",
                1L
        );

        assertThrows(
                IllegalStateException.class,
                () -> publish(
                        documentId,
                        incompatibleGeneration,
                        "incompatible-profile-chunk",
                        incompatibleProfile
                )
        );

        Long stillPublished = jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                """,
                Long.class,
                documentId
        );
        Integer incompatibleVectorCount = jdbc.queryForObject(
                "SELECT count(*) FROM akmai_vector.p_contract_other",
                Integer.class
        );

        assertEquals(publishedGeneration, stillPublished);
        assertEquals(0, incompatibleVectorCount);
    }

    private static EmbeddingProfile profile(String profileId, String tableName) {
        return new EmbeddingProfile(
                profileId,
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "contract",
                "akmai_vector",
                tableName,
                "NONE",
                (short) 1,
                Instant.parse("2026-10-06T00:00:00Z")
        );
    }

    private void publish(
            String documentId,
            long generation,
            String chunkId,
            EmbeddingProfile profile
    ) {
        SearchProjection projection = projection(documentId, generation, chunkId);
        String vectorId = VectorIdentity.physicalId(documentId, generation, chunkId);
        publication.publish(
                documentId,
                generation,
                RetentionPolicy.PERMANENT,
                null,
                profile,
                List.of(projection),
                List.of(),
                List.of(new VectorGenerationEntry(vectorId, chunkId)),
                List.of(vector(vectorId, projection, profile))
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
                null,
                0,
                "canonical evidence " + chunkId,
                "embedding evidence " + chunkId,
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", "publication-contract"),
                2
        );
    }

    private VectorRow vector(
            String vectorId,
            SearchProjection projection,
            EmbeddingProfile profile
    ) {
        return new VectorRow(
                vectorId,
                projection.chunkId(),
                projection.language(),
                projection.embeddingText(),
                Map.of(
                        "akmaiMetadataVersion", 2,
                        "akmaiDocumentId", projection.documentId(),
                        "akmaiGeneration", projection.generation(),
                        "akmaiEmbeddingProfileId", profile.profileId(),
                        "akmaiChunkId", projection.chunkId(),
                        "language", projection.language()
                ),
                new float[] {1f, 0f, 0f}
        );
    }
}
