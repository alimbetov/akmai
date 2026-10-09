package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.chunking.AtomicUnitProtector;
import kz.alimbetov.akmai.knowledge.chunking.ChunkIdentity;
import kz.alimbetov.akmai.knowledge.chunking.ChunkingProperties;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.chunking.DomainSemanticClassifier;
import kz.alimbetov.akmai.knowledge.chunking.EmbeddingTextBuilder;
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.chunking.OversizedUnitSplitter;
import kz.alimbetov.akmai.knowledge.chunking.ParentChildProperties;
import kz.alimbetov.akmai.knowledge.chunking.SemanticChunker;
import kz.alimbetov.akmai.knowledge.chunking.StructuralUnitExtractor;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.embedding.GenerationEmbeddingService;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionService;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import kz.alimbetov.akmai.knowledge.vector.PublishedVectorSearchRepository;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
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
class KnowledgeIngestionPostgresSmokeIntegrationTest {

    private static final String DOCUMENT_ID = "smoke-pg-large-1";
    private static final String VECTOR_TABLE = "p_smoke_ingestion";
    private static final String CANONICAL_SOURCE_HASH = "sha256:" + "b".repeat(64);

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
    static EmbeddingModel embeddingModel;
    static KnowledgeIngestionService ingestion;
    static PublishedVectorSearchRepository vectorSearch;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("SELECT akmai_admin.ensure_access_level(1)");

        ObjectMapper mapper = new ObjectMapper();
        storage = new EmbeddingProfileStorageManager(jdbc);
        profile = new EmbeddingProfile(
                "ep-smoke-ingestion",
                "test",
                "deterministic-fake",
                8,
                "COSINE_DISTANCE",
                "smoke-tokenizer",
                "smoke-ingestion",
                "akmai_vector",
                VECTOR_TABLE,
                "HNSW",
                (short) 1,
                Instant.parse("2026-10-09T00:00:00Z")
        );
        storage.ensureStorage(profile);
        new EmbeddingProfileRepository(jdbc).save(profile);
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

        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(KnowledgeIngestionPostgresSmokeIntegrationTest::fakeVector)
                    .toList();
        });

        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        when(profiles.activeProfile()).thenReturn(profile);

        IngestionIdempotencyRepository idempotency =
                mock(IngestionIdempotencyRepository.class);
        DocumentGenerationRepository generations =
                new DocumentGenerationRepository(jdbc, tx);
        PostgresSearchProjectionRepository projections =
                new PostgresSearchProjectionRepository(jdbc, mapper);
        DocumentIdentifierRepository identifiers =
                new DocumentIdentifierRepository(jdbc);
        VectorGenerationRepository manifests =
                new VectorGenerationRepository(jdbc);
        ReferenceGraphRepository references = new ReferenceGraphRepository(
                jdbc,
                new CrossReferenceExtractor()
        );
        PostgresGenerationVectorRepository vectors =
                new PostgresGenerationVectorRepository(jdbc, mapper, storage);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        GenerationPublicationService publication = new GenerationPublicationService(
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
        PersistenceCoordinator persistence = new PersistenceCoordinator(
                new SearchProjectionFactory(),
                generations,
                profiles,
                new GenerationEmbeddingService(embeddingModel),
                publication,
                resolver,
                idempotency,
                new IdempotencyProperties(Duration.ofMinutes(5)),
                retentionProperties()
        );

        Executor directExecutor = Runnable::run;
        ParallelIngestionExecutor enrichment = new ParallelIngestionExecutor(
                new IdentifierExtractor(List.of()),
                directExecutor
        );
        ingestion = new KnowledgeIngestionService(
                productionLikeChunker(),
                enrichment,
                persistence,
                idempotency,
                new CanonicalRequestFingerprint(mapper),
                new IdempotencyProperties(Duration.ofMinutes(5))
        );

        vectorSearch = new PublishedVectorSearchRepository(
                jdbc,
                mapper,
                embeddingModel,
                profiles,
                storage,
                tx
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM akmai_vector." + VECTOR_TABLE);
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
    void largeDocumentIsChunkedEmbeddedPublishedAndRetrievalVisibleInPostgres() {
        AddKnowledgeRequest request = new AddKnowledgeRequest(
                DOCUMENT_ID,
                "Postgres ingestion smoke fixture",
                largeLegalText(),
                "postgres-smoke",
                "en",
                KnowledgeDomain.LEGAL,
                1L,
                Map.of("testKind", "postgres-ingestion-smoke")
        );

        var response = ingestion.addText(request);

        Long generation = jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                """,
                Long.class,
                DOCUMENT_ID
        );
        assertThat(generation).isNotNull().isPositive();
        assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(response.chunkCount()).isGreaterThan(5);

        assertThat(count(
                "knowledge_document_generation",
                "document_id = ? AND generation = ? AND generation_status = 'PUBLISHED'",
                DOCUMENT_ID,
                generation
        )).isEqualTo(1);
        assertThat(generationChunkCount(DOCUMENT_ID, generation))
                .isEqualTo(response.chunkCount());
        assertThat(count(
                "knowledge_document_vector_generation",
                "document_id = ? AND generation = ?",
                DOCUMENT_ID,
                generation
        )).isEqualTo(response.chunkCount());
        assertThat(count(
                "akmai_vector." + VECTOR_TABLE,
                "document_id = ? AND generation = ?",
                DOCUMENT_ID,
                generation
        )).isEqualTo(response.chunkCount());
        assertThat(count(
                "knowledge_search_projection",
                "document_id = ? AND generation = ?",
                DOCUMENT_ID,
                generation
        )).isGreaterThanOrEqualTo(response.chunkCount());

        String targetEmbeddingText = firstVectorContent(DOCUMENT_ID, generation);
        when(embeddingModel.embed("smoke-query"))
                .thenReturn(fakeVector(targetEmbeddingText));

        var matches = vectorSearch.search(
                "smoke-query",
                List.of(DOCUMENT_ID),
                Set.of(1L),
                3,
                0.99
        );

        assertThat(matches).isNotEmpty();
        assertThat(matches).allSatisfy(match -> {
            assertThat(match.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(match.generation()).isEqualTo(generation);
            assertThat(match.score()).isGreaterThanOrEqualTo(0.99);
        });
    }

    @Test
    void canonicalFileServiceDocumentPublishesAndRetainsTypedRetrievalProvenance() {
        String documentId = "smoke-pg-canonical-1";
        CanonicalKnowledgeDocument document = canonicalDocument(documentId);

        var result = ingestion.addCanonicalKnowledge(document, null);

        assertThat(result.publication().status().name()).isEqualTo("PUBLISHED");
        assertThat(result.publication().generation()).isPositive();
        assertThat(result.publication().chunkCount()).isPositive();
        assertThat(result.processing().canonicalHash()).isNotBlank();
        assertThat(result.processing().embeddingProfile()).isEqualTo(profile.profileId());

        long generation = result.publication().generation();
        assertThat(jdbc.queryForObject(
                """
                SELECT published_generation
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                """,
                Long.class,
                documentId
        )).isEqualTo(generation);
        assertThat(generationChunkCount(documentId, generation))
                .isEqualTo(result.publication().chunkCount());
        assertThat(count(
                "knowledge_document_vector_generation",
                "document_id = ? AND generation = ?",
                documentId,
                generation
        )).isEqualTo(result.publication().chunkCount());

        String targetEmbeddingText = firstVectorContent(documentId, generation);
        when(embeddingModel.embed("canonical-smoke-query"))
                .thenReturn(fakeVector(targetEmbeddingText));

        var matches = vectorSearch.search(
                "canonical-smoke-query",
                List.of(documentId),
                Set.of(1L),
                3,
                0.99
        );
        assertThat(matches).isNotEmpty();

        var match = matches.getFirst();
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.VECTOR,
                match.accessLevel(),
                match.documentId(),
                match.generation(),
                match.chunkId(),
                match.content(),
                match.metadata()
        );

        assertThat(hit.documentId()).isEqualTo(documentId);
        assertThat(hit.generation()).isEqualTo(generation);
        assertThat(hit.sourceProvenance()).isNotNull();
        assertThat(hit.sourceProvenance().fileId()).isEqualTo("file-smoke-1");
        assertThat(hit.sourceProvenance().sourceVersion()).isEqualTo("7");
        assertThat(hit.sourceProvenance().fileName()).isEqualTo("architecture.pdf");
        assertThat(hit.sourceProvenance().contentHash())
                .isEqualTo(CANONICAL_SOURCE_HASH);
        assertThat(hit.sourceProvenance().blockIds()).isNotEmpty();
        assertThat(hit.sourceProvenance().pageFrom()).isNotNull().isPositive();
        assertThat(hit.sourceProvenance().pageTo())
                .isNotNull()
                .isGreaterThanOrEqualTo(hit.sourceProvenance().pageFrom());
        assertThat(hit.metadata())
                .doesNotContainKeys(
                        "storageProvider",
                        "storageBucket",
                        "storageObjectKey",
                        "presignedUrl",
                        "authorization"
                );
    }

    private static int generationChunkCount(String documentId, long generation) {
        Integer count = jdbc.queryForObject(
                """
                SELECT chunk_count
                FROM knowledge_document_generation
                WHERE document_id = ? AND generation = ?
                """,
                Integer.class,
                documentId,
                generation
        );
        return count == null ? 0 : count;
    }

    private static String firstVectorContent(String documentId, long generation) {
        return jdbc.queryForObject(
                """
                SELECT content
                FROM akmai_vector.%s
                WHERE document_id = ? AND generation = ?
                ORDER BY chunk_id
                LIMIT 1
                """.formatted(VECTOR_TABLE),
                String.class,
                documentId,
                generation
        );
    }

    private static int count(
            String table,
            String predicate,
            Object... args
    ) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + predicate,
                Integer.class,
                args
        );
        return count == null ? 0 : count;
    }

    private static float[] fakeVector(String text) {
        int dimensions = profile == null ? 8 : profile.dimensions();
        float[] vector = new float[dimensions];
        int seed = text == null ? 0 : text.hashCode();
        double norm = 0.0;
        for (int index = 0; index < dimensions; index++) {
            int mixed = Integer.rotateLeft(
                    seed ^ (0x9E3779B9 * (index + 1)),
                    (index + 1) & 31
            );
            float value = ((mixed & 0xffff) / 32767.5f) - 1.0f;
            vector[index] = value;
            norm += value * value;
        }
        if (norm == 0.0) {
            vector[0] = 1.0f;
            return vector;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
        return vector;
    }

    private static HierarchicalChunker productionLikeChunker() {
        TokenEstimator estimator = new TokenEstimator();
        TextNormalizer normalizer = new TextNormalizer();
        EmbeddingTextBuilder embeddingTextBuilder = new EmbeddingTextBuilder();
        CrossReferenceExtractor references = new CrossReferenceExtractor();
        SemanticChunker semantic = new SemanticChunker(
                normalizer,
                new StructuralUnitExtractor(),
                new DomainSemanticClassifier(),
                new AtomicUnitProtector(),
                references,
                embeddingTextBuilder,
                estimator,
                new ChunkingProperties(750, 1200, 1800, 100),
                new OversizedUnitSplitter(estimator),
                new ChunkIdentity()
        );
        return new HierarchicalChunker(
                semantic,
                new ParentChildProperties(true, 250, 275, 300, true, 8),
                estimator,
                new OversizedUnitSplitter(estimator),
                embeddingTextBuilder,
                normalizer,
                references,
                new ChunkIdentity()
        );
    }

    private static RetentionProperties retentionProperties() {
        return new RetentionProperties(
                true,
                "0 30 3 * * *",
                "UTC",
                100,
                20,
                5,
                4,
                16,
                Duration.ofMinutes(10),
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
    }

    private CanonicalKnowledgeDocument canonicalDocument(String documentId) {
        List<CanonicalKnowledgeDocument.Block> blocks = IntStream.rangeClosed(1, 24)
                .mapToObj(index -> new CanonicalKnowledgeDocument.Block(
                        "b-" + index,
                        CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                        "Architecture evidence block " + index
                                + ". PostgreSQL remains the durable authority and publication must preserve source provenance, generation identity and retrieval visibility.",
                        null,
                        index,
                        index,
                        List.of("Architecture", "Section " + index),
                        null
                ))
                .toList();
        return new CanonicalKnowledgeDocument(
                1,
                documentId,
                "7",
                "FileService canonical smoke fixture",
                "en",
                KnowledgeDomain.TECHNICAL,
                1L,
                new CanonicalKnowledgeDocument.Source(
                        CanonicalKnowledgeDocument.SourceType.FILE,
                        "file-smoke-1",
                        "7",
                        "architecture.pdf",
                        "application/pdf",
                        CANONICAL_SOURCE_HASH,
                        new CanonicalKnowledgeDocument.StorageReference(
                                "rustfs",
                                "knowledge-raw",
                                "tenant/files/file-smoke-1/v7.pdf",
                                "storage-v7"
                        )
                ),
                new CanonicalKnowledgeDocument.Processing(
                        "pdf-parser",
                        "4.2.0",
                        Instant.parse("2026-10-09T00:00:00Z")
                ),
                blocks,
                Map.of("testKind", "canonical-postgres-smoke")
        );
    }

    private String largeLegalText() {
        return IntStream.rangeClosed(1, 30)
                .mapToObj(index -> """
                        Chapter %d. Production qualification
                        Article %d. Ingestion and publication requirements
                        Paragraph 1. The service must preserve document identity, access scope, provenance and publication generation while processing this legal knowledge section.
                        Paragraph 2. The published generation must become visible only after all searchable fragments, vector rows and generation manifest entries have been written atomically.
                        Paragraph 3. The system must retain section ancestry, deterministic chunk identity and bounded fragment size. Failed processing must never expose a partial generation.
                        Paragraph 4. Monitoring and audit evidence are required for every publication decision, while retrieval must only observe the lifecycle-approved generation.
                        """.formatted(index, index))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
    }
}
