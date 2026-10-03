package kz.alimbetov.akmai.knowledge.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReferenceGraphIntegrationTest {

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
    static PostgresSearchProjectionRepository projections;
    static ReferenceGraphRepository references;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(ds);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(ds);
        jdbc.execute("SELECT akmai_admin.ensure_access_level(7)");
        projections = new PostgresSearchProjectionRepository(
                jdbc,
                new ObjectMapper()
        );
        references = new ReferenceGraphRepository(
                jdbc,
                new CrossReferenceExtractor()
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void normalGenerationRowsProduceResolvablePublishedReferenceGraph() {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, access_level
                ) VALUES (
                    'law-1', 'PERMANENT', 'READY',
                    1, 0, 0,
                    clock_timestamp(), clock_timestamp(),
                    'ACTIVE', 1, 2, 7
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, content_fingerprint,
                    physical_id_version, cleanup_required,
                    started_at, published_at, access_level
                ) VALUES (
                    'law-1', 1, 'PUBLISHED',
                    'INGESTION', 'fp', 2, false,
                    clock_timestamp(), clock_timestamp(), 7
                )
                """
        );

        SearchProjection target = projection(
                "article-25",
                0,
                "Статья 25. Расторжение договора"
        );
        SearchProjection source = projection(
                "article-30",
                1,
                "Последствия определяются статьёй 25 настоящего договора."
        );
        projections.saveAll(List.of(target, source));
        references.saveAll(List.of(target, source));

        List<String> resolved = references.resolveSameDocumentTargets(
                "law-1",
                List.of("article-30"),
                Set.of(7L),
                10
        );

        assertThat(resolved).containsExactly("article-25");
        assertThat(references.resolveSameDocumentTargets(
                "law-1",
                List.of("article-30"),
                Set.of(8L),
                10
        )).isEmpty();
        assertThatThrownBy(() -> references.resolveSameDocumentTargets(
                "law-1",
                List.of("article-30"),
                Set.of(),
                10
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessLevels");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_reference_target
                WHERE document_id = 'law-1'
                  AND generation = 1
                  AND chunk_id = 'article-25'
                  AND reference_type = 'ARTICLE'
                  AND canonical_value = '25'
                """,
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_reference_edge
                WHERE document_id = 'law-1'
                  AND generation = 1
                  AND source_chunk_id = 'article-25'
                """,
                Integer.class
        )).isZero();
    }

    private SearchProjection projection(
            String chunkId,
            int index,
            String text
    ) {
        return new SearchProjection(
                chunkId,
                "law-1",
                1L,
                null,
                index,
                text,
                text,
                "ru",
                KnowledgeDomain.LEGAL,
                "law > article",
                List.of(),
                List.of(),
                Map.of("source", "law.md"),
                2
        );
    }
}
