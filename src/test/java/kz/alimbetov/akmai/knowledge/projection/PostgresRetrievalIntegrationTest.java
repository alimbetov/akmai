package kz.alimbetov.akmai.knowledge.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
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
