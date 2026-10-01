package kz.alimbetov.akmai.knowledge.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.identifier.search.PostgresIdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentOperationLock;
import kz.alimbetov.akmai.knowledge.lifecycle.PostgresDocumentLifecycleRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.quality.RetrievalQualityMetrics;
import kz.alimbetov.akmai.rag.retrieval.KnowledgeExpansion;
import kz.alimbetov.akmai.rag.retrieval.ReferenceRetrievalStrategy;
import kz.alimbetov.akmai.rag.retrieval.RetrievalContext;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.mock;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresRetrievalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbcTemplate;
    static PostgresSearchProjectionRepository repository;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new PostgresSearchProjectionRepository(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void liquibaseCreatesCanonicalRetrievalSchema() {
        assertThat(tableExists("knowledge_search_projection")).isTrue();
        assertThat(tableExists("document_identifier")).isTrue();
        assertThat(indexExists("idx_knowledge_search_text_trgm")).isTrue();
        assertThat(indexExists("idx_knowledge_search_section_trgm")).isTrue();
    }

    @Test
    void replacementRemovesStaleLexicalProjection() {
        repository.saveAll(List.of(projection(
                "old-chunk",
                "doc-1",
                0,
                "legacy obsolete marker"
        )));

        assertThat(repository.searchLexical("obsolete", "en", List.of("doc-1"), 10))
                .extracting(SearchProjection::chunkId)
                .containsExactly("old-chunk");

        repository.deleteByDocumentId("doc-1");
        repository.saveAll(List.of(projection(
                "new-chunk",
                "doc-1",
                0,
                "current replacement marker"
        )));

        assertThat(repository.findChunkIdsByDocumentId("doc-1"))
                .containsExactly("new-chunk");
        assertThat(repository.searchLexical("obsolete", "en", List.of("doc-1"), 10))
                .isEmpty();
        assertThat(repository.searchLexical("replacement", "en", List.of("doc-1"), 10))
                .extracting(SearchProjection::chunkId)
                .containsExactly("new-chunk");
    }

    @Test
    void identifierReplacementLeavesNoStaleIdentifierState() {
        DocumentIdentifierRepository identifiers =
                new DocumentIdentifierRepository(jdbcTemplate);
        identifiers.saveAll(List.of(new DocumentIdentifier(
                "doc-ident",
                "old-ident-chunk",
                0,
                IdentifierType.DOCUMENT_NUMBER,
                "OLD-42",
                "OLD-42",
                "old context",
                Instant.now()
        )));

        assertThat(identifiers.findExact("OLD-42", 10)).hasSize(1);

        jdbcTemplate.update(
                "DELETE FROM document_identifier WHERE document_id = ?",
                "doc-ident"
        );
        identifiers.saveAll(List.of(new DocumentIdentifier(
                "doc-ident",
                "new-ident-chunk",
                0,
                IdentifierType.DOCUMENT_NUMBER,
                "NEW-43",
                "NEW-43",
                "new context",
                Instant.now()
        )));

        assertThat(identifiers.findExact("OLD-42", 10)).isEmpty();
        assertThat(identifiers.findExact("NEW-43", 10))
                .extracting(DocumentIdentifier::chunkId)
                .containsExactly("new-ident-chunk");
    }

    @Test
    void coordinatorReingestionReplacesCanonicalAndIdentifierStateTogether() {
        DocumentIdentifierRepository identifierRepository =
                new DocumentIdentifierRepository(jdbcTemplate);
        PostgresIdentifierSearchIndex identifierIndex =
                new PostgresIdentifierSearchIndex(
                        identifierRepository,
                        new IdentifierNormalizer(),
                        jdbcTemplate
                );
        VectorStore vectorStore = mock(VectorStore.class);
        PersistenceCoordinator coordinator = new PersistenceCoordinator(
                vectorStore,
                identifierIndex,
                new SearchProjectionFactory(),
                repository,
                new PostgresDocumentLifecycleRepository(
                        jdbcTemplate,
                        new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()))
                ),
                new VectorGenerationRepository(jdbcTemplate),
                new DocumentOperationLock(jdbcTemplate.getDataSource()),
                new RetentionProperties(
                        true, "0 30 3 * * *", "UTC", 100, 20, 5,
                        4, 16, Duration.ofMinutes(10),
                        RetentionPolicy.PERMANENT, Duration.ofDays(90)
                )
        );

        coordinator.persist(List.of(enriched(
                "old-coordinator",
                "doc-coordinator",
                "legacy obsolete coordinator",
                "OLD-COORD"
        )));
        coordinator.persist(List.of(enriched(
                "new-coordinator",
                "doc-coordinator",
                "current coordinator replacement",
                "NEW-COORD"
        )));

        assertThat(repository.findChunkIdsByDocumentId("doc-coordinator"))
                .containsExactly("new-coordinator");
        assertThat(repository.searchLexical(
                "obsolete",
                "en",
                List.of("doc-coordinator"),
                10
        )).isEmpty();
        assertThat(identifierRepository.findExact("OLD-COORD", 10)).isEmpty();
        assertThat(identifierRepository.findExact("NEW-COORD", 10))
                .extracting(DocumentIdentifier::chunkId)
                .containsExactly("new-coordinator");
    }

    @Test
    void referenceResolutionUsesPostgresProjectionAndCanonicalTargetText() {
        DocumentIdentifierRepository identifierRepository =
                new DocumentIdentifierRepository(jdbcTemplate);
        PostgresIdentifierSearchIndex identifierIndex =
                new PostgresIdentifierSearchIndex(
                        identifierRepository,
                        new IdentifierNormalizer(),
                        jdbcTemplate
                );
        SearchProjection seed = projection(
                "ref-seed",
                "doc-ref",
                0,
                "См. документ REF-48."
        );
        seed = new SearchProjection(
                seed.chunkId(),
                seed.documentId(),
                seed.parentChunkId(),
                seed.chunkIndex(),
                seed.text(),
                seed.embeddingText(),
                "ru",
                seed.domain(),
                seed.sectionPath(),
                seed.identifiers(),
                List.of("REF-48"),
                seed.metadata(),
                seed.projectionVersion()
        );
        SearchProjection target = projection(
                "ref-target",
                "doc-ref-target",
                0,
                "Канонический текст целевого документа."
        );
        repository.saveAll(List.of(seed, target));
        identifierRepository.saveAll(List.of(new DocumentIdentifier(
                "doc-ref-target",
                "ref-target",
                0,
                IdentifierType.DOCUMENT_NUMBER,
                "REF-48",
                new IdentifierNormalizer().normalize("REF-48"),
                "short context",
                Instant.now()
        )));

        ReferenceRetrievalStrategy strategy =
                new ReferenceRetrievalStrategy(repository, identifierIndex, RetrievalTestProperties.defaults());
        List<RetrievalHit> hits = strategy.retrieve(
                new QueryChunk("q-ref", 0, "REF-48", "REF-48", "REF-48", "ru", List.of()),
                new RetrievalContext(List.of(new RetrievalHit(
                        RetrievalType.LEXICAL,
                        "doc-ref",
                        "ref-seed",
                        seed.text(),
                        Map.of()
                )))
        );

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().chunkId()).isEqualTo("ref-target");
        assertThat(hits.getFirst().text())
                .isEqualTo("Канонический текст целевого документа.");
    }

    @Test
    void neighborExpansionUsesPostgresCanonicalCoordinatesAndOrdering() {
        repository.saveAll(List.of(
                projection("neighbor-0", "doc-neighbor", 0, "before"),
                projection("neighbor-1", "doc-neighbor", 1, "seed"),
                projection("neighbor-2", "doc-neighbor", 2, "after")
        ));

        KnowledgeExpansion expansion = new KnowledgeExpansion(repository, RetrievalTestProperties.defaults());
        List<RetrievalHit> expanded = expansion.expand(List.of(
                new RetrievalHit(
                        RetrievalType.VECTOR,
                        "doc-neighbor",
                        "neighbor-1",
                        "seed",
                        Map.of()
                )
        ));

        assertThat(expanded)
                .extracting(RetrievalHit::chunkId)
                .containsExactly("neighbor-1", "neighbor-0", "neighbor-2");
        assertThat(expanded.subList(1, expanded.size()))
                .allSatisfy(hit -> assertThat(hit.metadata())
                        .containsEntry("expansion", "neighbor"));
    }

    @Test
    void lexicalIndexSupportsExactTokensAcrossTargetLanguages() {
        repository.saveAll(List.of(
                projection("kk", "doc-kk", 0, "келісімшарт төлем мерзімі", "kk"),
                projection("ru", "doc-ru", 0, "договор срок оплаты", "ru"),
                projection("en", "doc-en", 0, "contract payment deadline", "en"),
                projection("zh", "doc-zh", 0, "合同付款期限", "zh")
        ));

        assertThat(repository.searchLexical("келісімшарт", "kk", List.of(), 10))
                .extracting(SearchProjection::chunkId).containsExactly("kk");
        assertThat(repository.searchLexical("договоры", "ru", List.of(), 10))
                .extracting(SearchProjection::chunkId).containsExactly("ru");
        assertThat(repository.searchLexical("contracts", "en", List.of(), 10))
                .extracting(SearchProjection::chunkId).containsExactly("en");
        assertThat(repository.searchLexical("付款期限", "zh", List.of(), 10))
                .extracting(SearchProjection::chunkId).containsExactly("zh");
    }


    @Test
    void corpusBackedLexicalQualityGateCoversAllTargetLanguages() {
        assertLexicalQuality(
                "kk",
                "шартты бұзу",
                "quality-kk-target",
                "Шартты бұзу талаптары және хабарлау тәртібі.",
                List.of(
                        projection("quality-kk-noise-1", "quality-kk-doc-1", 0, "Төлем мерзімі туралы жалпы ереже.", "kk"),
                        projection("quality-kk-target", "quality-kk-doc-2", 0, "Шартты бұзу талаптары және хабарлау тәртібі.", "kk"),
                        projection("quality-kk-noise-2", "quality-kk-doc-3", 0, "Құжаттарды сақтау мерзімі.", "kk")
                )
        );

        assertLexicalQuality(
                "ru",
                "условия расторжения",
                "quality-ru-target",
                "Условия расторжения договора и порядок уведомления.",
                List.of(
                        projection("quality-ru-noise-1", "quality-ru-doc-1", 0, "Срок оплаты по договору.", "ru"),
                        projection("quality-ru-target", "quality-ru-doc-2", 0, "Условия расторжения договора и порядок уведомления.", "ru"),
                        projection("quality-ru-noise-2", "quality-ru-doc-3", 0, "Порядок хранения документов.", "ru")
                )
        );

        assertLexicalQuality(
                "en",
                "contract termination",
                "quality-en-target",
                "Contract termination conditions and notice procedure.",
                List.of(
                        projection("quality-en-noise-1", "quality-en-doc-1", 0, "Payment deadline under the agreement.", "en"),
                        projection("quality-en-target", "quality-en-doc-2", 0, "Contract termination conditions and notice procedure.", "en"),
                        projection("quality-en-noise-2", "quality-en-doc-3", 0, "Document retention requirements.", "en")
                )
        );

        assertLexicalQuality(
                "zh",
                "合同终止",
                "quality-zh-target",
                "合同终止条件和通知程序。",
                List.of(
                        projection("quality-zh-noise-1", "quality-zh-doc-1", 0, "合同付款期限。", "zh"),
                        projection("quality-zh-target", "quality-zh-doc-2", 0, "合同终止条件和通知程序。", "zh"),
                        projection("quality-zh-noise-2", "quality-zh-doc-3", 0, "文件保存要求。", "zh")
                )
        );
    }

    private static EnrichedKnowledgeChunk enriched(
            String chunkId,
            String documentId,
            String text,
            String identifierValue
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                chunkId,
                documentId,
                null,
                0,
                text,
                text,
                text,
                "integration",
                "integration",
                "en",
                KnowledgeDomain.GENERAL,
                List.of(),
                Map.of("source", "integration")
        );
        return new EnrichedKnowledgeChunk(
                chunk,
                List.of(new kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier(
                        IdentifierType.DOCUMENT_NUMBER,
                        identifierValue,
                        identifierValue,
                        text
                )),
                List.of()
        );
    }


    private static boolean indexExists(String index) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND indexname = ?
                """,
                Integer.class,
                index
        );
        return count != null && count == 1;
    }

    private static boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name = ?
                """,
                Integer.class,
                table
        );
        return count != null && count == 1;
    }


    private void assertLexicalQuality(
            String language,
            String query,
            String relevantChunkId,
            String relevantText,
            List<SearchProjection> corpus
    ) {
        repository.saveAll(corpus);
        List<String> documentIds = corpus.stream()
                .map(SearchProjection::documentId)
                .toList();
        List<String> ranked = repository.searchLexical(
                        query,
                        language,
                        documentIds,
                        5
                ).stream()
                .map(SearchProjection::chunkId)
                .toList();

        assertThat(ranked).contains(relevantChunkId);
        assertThat(RetrievalQualityMetrics.recallAtK(
                ranked,
                java.util.Set.of(relevantChunkId),
                5
        )).isEqualTo(1.0);
        assertThat(RetrievalQualityMetrics.reciprocalRank(
                ranked,
                java.util.Set.of(relevantChunkId)
        )).isEqualTo(1.0);
        assertThat(RetrievalQualityMetrics.ndcgAtK(
                ranked,
                java.util.Set.of(relevantChunkId),
                5
        )).isEqualTo(1.0);
        assertThat(corpus.stream()
                .filter(item -> item.chunkId().equals(relevantChunkId))
                .findFirst()
                .orElseThrow()
                .text()).isEqualTo(relevantText);
    }

    private static SearchProjection projection(
            String chunkId,
            String documentId,
            int chunkIndex,
            String text
    ) {
        return projection(chunkId, documentId, chunkIndex, text, "en");
    }

    private static SearchProjection projection(
            String chunkId,
            String documentId,
            int chunkIndex,
            String text,
            String language
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                null,
                chunkIndex,
                text,
                text,
                language,
                KnowledgeDomain.GENERAL,
                "integration",
                List.of(),
                List.of(),
                Map.of(),
                1
        );
    }
}
