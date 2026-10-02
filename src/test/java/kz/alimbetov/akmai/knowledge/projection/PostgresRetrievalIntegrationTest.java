package kz.alimbetov.akmai.knowledge.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
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

@Testcontainers
class PostgresRetrievalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static PostgresSearchProjectionRepository projections;
    static DocumentIdentifierRepository identifiers;
    static DocumentGenerationRepository generations;

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
        projections = new PostgresSearchProjectionRepository(
                jdbc,
                new ObjectMapper()
        );
        identifiers = new DocumentIdentifierRepository(jdbc);
        generations = new DocumentGenerationRepository(
                jdbc,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                )
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void lexicalReadReturnsOnlyPublishedGeneration() {
        long first = generations.allocate(
                "doc",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fp-1"
        );
        publish("doc", first);
        projections.saveAll(List.of(
                projection("old", first, "obsolete phrase", "en")
        ));

        long second = generations.allocate(
                "doc",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fp-2"
        );
        projections.saveAll(List.of(
                projection("new", second, "canonical policy phrase", "en")
        ));
        publish("doc", second);

        assertThat(projections.searchLexical(
                "canonical policy phrase",
                "en",
                List.of("doc"),
                10
        ))
                .extracting(SearchProjection::chunkId)
                .containsExactly("new");

        assertThat(projections.findByChunkIds(List.of("old", "new")))
                .extracting(SearchProjection::chunkId)
                .containsExactly("new");
    }

    @Test
    void identifierReadReturnsOnlyPublishedGeneration() {
        long first = generations.allocate(
                "doc",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fp-1"
        );
        identifiers.saveAll(List.of(identifier(first, "old")));
        publish("doc", first);

        long second = generations.allocate(
                "doc",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fp-2"
        );
        identifiers.saveAll(List.of(identifier(second, "new")));
        publish("doc", second);

        assertThat(identifiers.findExact(
                IdentifierType.DOCUMENT_NUMBER,
                "DOC-42",
                10
        ))
                .extracting(DocumentIdentifier::chunkId)
                .containsExactly("new");
    }

    @Test
    void lexicalFtsUsesLanguageSpecificRussianAndEnglishVectors() {
        long generation = generations.allocate(
                "doc",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fp-fts"
        );
        projections.saveAll(List.of(
                projection(
                        "ru-law",
                        generation,
                        "Банк расторгает договор при существенном нарушении.",
                        "ru"
                ),
                projection(
                        "en-law",
                        generation,
                        "Agreement termination rules apply after material breach.",
                        "en"
                )
        ));
        publish("doc", generation);

        assertThat(projections.searchLexical(
                "расторгнуть договор",
                "ru",
                List.of("doc"),
                10
        )).extracting(SearchProjection::chunkId)
                .contains("ru-law")
                .doesNotContain("en-law");

        assertThat(projections.searchLexical(
                "termination agreement",
                "en",
                List.of("doc"),
                10
        )).extracting(SearchProjection::chunkId)
                .contains("en-law")
                .doesNotContain("ru-law");
    }


    @Test
    void languageSpecificFtsQueriesUseTheirGeneratedGinIndexes() {
        long generation = generations.allocate(
                "doc",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fp-explain"
        );
        projections.saveAll(List.of(
                projection(
                        "ru-explain",
                        generation,
                        "Договор расторгается банком.",
                        "ru"
                ),
                projection(
                        "en-explain",
                        generation,
                        "Agreement termination is permitted.",
                        "en"
                )
        ));
        publish("doc", generation);

        String ruPlan = explainWithSequentialScanDisabled(
                "search_vector_ru",
                "russian",
                "договор"
        );
        String enPlan = explainWithSequentialScanDisabled(
                "search_vector_en",
                "english",
                "agreement"
        );

        assertThat(ruPlan).contains("idx_knowledge_search_fts_ru");
        assertThat(enPlan).contains("idx_knowledge_search_fts_en");
    }

    @Test
    void kkAndZhTrigramSearchTreatsLikeMetacharactersLiterally() {
        long generation = generations.allocate(
                "doc",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fp-like"
        );
        projections.saveAll(List.of(
                projection(
                        "kk-percent",
                        generation,
                        "Жеңілдік мөлшері 5% болады.",
                        "kk"
                ),
                projection(
                        "kk-plain",
                        generation,
                        "Жеңілдік мөлшері бес пайыз болады.",
                        "kk"
                ),
                projection(
                        "zh-underscore",
                        generation,
                        "技术代码 A_B 已登记。",
                        "zh"
                ),
                projection(
                        "zh-plain",
                        generation,
                        "技术代码 AXB 已登记。",
                        "zh"
                )
        ));
        publish("doc", generation);

        assertThat(projections.searchLexical(
                "%",
                "kk",
                List.of("doc"),
                10
        )).extracting(SearchProjection::chunkId)
                .containsExactly("kk-percent");

        assertThat(projections.searchLexical(
                "_",
                "zh",
                List.of("doc"),
                10
        )).extracting(SearchProjection::chunkId)
                .containsExactly("zh-underscore");

        assertThat(projections.searchLexical(
                "5%",
                "kk",
                List.of("doc"),
                10
        )).extracting(SearchProjection::chunkId)
                .contains("kk-percent")
                .doesNotContain("kk-plain");
    }


    private String explainWithSequentialScanDisabled(
            String vectorColumn,
            String configuration,
            String query
    ) {
        return jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>)
                        connection -> {
                            try (var setting = connection.createStatement()) {
                                setting.execute("SET enable_seqscan = off");
                            }
                            try (var statement = connection.prepareStatement(
                                    """
                                    EXPLAIN (COSTS OFF)
                                    SELECT chunk_id
                                    FROM knowledge_search_projection
                                    WHERE %s
                                          @@ websearch_to_tsquery('%s', ?)
                                    """.formatted(
                                            vectorColumn,
                                            configuration
                                    )
                            )) {
                                statement.setString(1, query);
                                try (var resultSet = statement.executeQuery()) {
                                    StringBuilder plan = new StringBuilder();
                                    while (resultSet.next()) {
                                        if (!plan.isEmpty()) {
                                            plan.append('\n');
                                        }
                                        plan.append(resultSet.getString(1));
                                    }
                                    return plan.toString();
                                }
                            } finally {
                                try (var reset = connection.createStatement()) {
                                    reset.execute("RESET enable_seqscan");
                                }
                            }
                        }
        );
    }

    private void publish(String documentId, long generation) {
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRED',
                    retired_at = clock_timestamp()
                WHERE document_id = ?
                  AND generation_status = 'PUBLISHED'
                """,
                documentId
        );
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'PUBLISHED',
                    published_at = clock_timestamp()
                WHERE document_id = ?
                  AND generation = ?
                """,
                documentId,
                generation
        );
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET published_generation = ?,
                    generation = ?,
                    lifecycle_status = 'READY',
                    retention_status = 'ACTIVE'
                WHERE document_id = ?
                """,
                generation,
                generation,
                documentId
        );
    }

    private SearchProjection projection(
            String chunkId,
            long generation,
            String text,
            String language
    ) {
        return new SearchProjection(
                chunkId,
                "doc",
                generation,
                null,
                Math.floorMod(chunkId.hashCode(), 1_000_000),
                text,
                text,
                language,
                KnowledgeDomain.GENERAL,
                "integration",
                List.of(),
                List.of(),
                Map.of("source", "integration"),
                2
        );
    }

    private DocumentIdentifier identifier(
            long generation,
            String chunkId
    ) {
        return new DocumentIdentifier(
                "doc",
                generation,
                chunkId,
                1,
                IdentifierType.DOCUMENT_NUMBER,
                "DOC-42",
                "DOC-42",
                "context",
                Instant.parse("2026-10-02T00:00:00Z")
        );
    }
}
