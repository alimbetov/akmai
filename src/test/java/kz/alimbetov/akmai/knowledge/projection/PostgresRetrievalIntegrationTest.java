package kz.alimbetov.akmai.knowledge.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
    }

    @Test
    void replacementRemovesStaleLexicalProjection() {
        repository.saveAll(List.of(projection(
                "old-chunk",
                "doc-1",
                0,
                "legacy obsolete marker"
        )));

        assertThat(repository.searchLexical("obsolete", List.of("doc-1"), 10))
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
        assertThat(repository.searchLexical("obsolete", List.of("doc-1"), 10))
                .isEmpty();
        assertThat(repository.searchLexical("replacement", List.of("doc-1"), 10))
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
                repository
        );

        coordinator.persist(List.of(enriched(
                "old-coordinator",
                "doc-coordinator",
                "legacy obsolete coordinator",
                "OLD-COORD"
        )));
        when(vectorStore.delete(List.of("old-coordinator"))).thenReturn(true);
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
                List.of("doc-coordinator"),
                10
        )).isEmpty();
        assertThat(identifierRepository.findExact("OLD-COORD", 10)).isEmpty();
        assertThat(identifierRepository.findExact("NEW-COORD", 10))
                .extracting(DocumentIdentifier::chunkId)
                .containsExactly("new-coordinator");
    }

    @Test
    void lexicalIndexSupportsExactTokensAcrossTargetLanguages() {
        repository.saveAll(List.of(
                projection("kk", "doc-kk", 0, "келісімшарт төлем мерзімі"),
                projection("ru", "doc-ru", 0, "договор срок оплаты"),
                projection("en", "doc-en", 0, "contract payment deadline"),
                projection("zh", "doc-zh", 0, "合同 付款 期限")
        ));

        assertThat(repository.searchLexical("келісімшарт", List.of(), 10))
                .extracting(SearchProjection::chunkId).contains("kk");
        assertThat(repository.searchLexical("договор", List.of(), 10))
                .extracting(SearchProjection::chunkId).contains("ru");
        assertThat(repository.searchLexical("contract", List.of(), 10))
                .extracting(SearchProjection::chunkId).contains("en");
        assertThat(repository.searchLexical("合同", List.of(), 10))
                .extracting(SearchProjection::chunkId).contains("zh");
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

    private static SearchProjection projection(
            String chunkId,
            String documentId,
            int chunkIndex,
            String text
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                null,
                chunkIndex,
                text,
                text,
                "und",
                KnowledgeDomain.GENERAL,
                "integration",
                List.of(),
                List.of(),
                Map.of(),
                1
        );
    }
}
