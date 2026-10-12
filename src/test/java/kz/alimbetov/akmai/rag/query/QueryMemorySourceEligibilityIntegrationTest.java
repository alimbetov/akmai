package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
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
class QueryMemorySourceEligibilityIntegrationTest {

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
    static QueryMemorySourceEligibility eligibility;

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

        jdbc = new JdbcTemplate(dataSource);
        jdbc.query("SELECT akmai_admin.ensure_access_level(?)", rs -> { }, 1L);
        jdbc.query("SELECT akmai_admin.ensure_access_level(?)", rs -> { }, 2L);
        eligibility = new QueryMemorySourceEligibility(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void keepsOnlyObservationsWhoseEntireSourceSetIsCurrentlyOnline() {
        seedPublished("live-doc", 1L, 1L, "live-chunk", false);
        seedPublished("expired-doc", 1L, 1L, "expired-chunk", true);

        SemanticQueryMemory.MemoryObservation live = observation(
                "live",
                List.of("live-doc:live-chunk")
        );
        SemanticQueryMemory.MemoryObservation mixed = observation(
                "mixed",
                List.of("live-doc:live-chunk", "expired-doc:expired-chunk")
        );
        SemanticQueryMemory.MemoryObservation malformed = observation(
                "malformed",
                List.of("missing-separator")
        );

        List<SemanticQueryMemory.MemoryMatch> filtered = eligibility.filter(
                List.of(match("cluster", live, mixed, malformed)),
                Set.of(1L)
        );

        assertThat(filtered).singleElement().satisfies(result -> {
            assertThat(result.clusterId()).isEqualTo("cluster");
            assertThat(result.observations())
                    .extracting(SemanticQueryMemory.MemoryObservation::groundedAnswer)
                    .containsExactly("live");
        });
    }

    @Test
    void rejectsWrongAclSupersededGenerationAndNonReadyLifecycle() {
        seedPublished("acl-doc", 1L, 2L, "acl-chunk", false);
        seedPublished("superseded-doc", 1L, 1L, "old-chunk", false);
        supersede("superseded-doc", 1L, 2L, 1L);
        seedPublished("ingesting-doc", 1L, 1L, "ingesting-chunk", false);
        jdbc.update(
                "UPDATE knowledge_document_lifecycle SET lifecycle_status = 'INGESTING' WHERE document_id = ?",
                "ingesting-doc"
        );

        List<SemanticQueryMemory.MemoryMatch> filtered = eligibility.filter(
                List.of(
                        match("acl", observation("acl", List.of("acl-doc:acl-chunk"))),
                        match("old", observation("old", List.of("superseded-doc:old-chunk"))),
                        match("ingesting", observation(
                                "ingesting",
                                List.of("ingesting-doc:ingesting-chunk")
                        ))
                ),
                Set.of(1L)
        );

        assertThat(filtered).isEmpty();
    }

    @Test
    void emptyCallerScopeFailsClosed() {
        seedPublished("live-doc", 1L, 1L, "live-chunk", false);

        assertThat(eligibility.filter(
                List.of(match(
                        "cluster",
                        observation("live", List.of("live-doc:live-chunk"))
                )),
                Set.of()
        )).isEmpty();
    }

    private SemanticQueryMemory.MemoryMatch match(
            String clusterId,
            SemanticQueryMemory.MemoryObservation... observations
    ) {
        return new SemanticQueryMemory.MemoryMatch(
                clusterId,
                0.95,
                observations.length,
                List.of(observations)
        );
    }

    private SemanticQueryMemory.MemoryObservation observation(
            String answer,
            List<String> sourceRefs
    ) {
        return new SemanticQueryMemory.MemoryObservation(
                "question",
                answer,
                sourceRefs,
                Instant.parse("2026-10-12T00:00:00Z")
        );
    }

    private void seedPublished(
            String documentId,
            long generation,
            long accessLevel,
            String chunkId,
            boolean expired
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id,
                    lifecycle_policy,
                    lifecycle_status,
                    generation,
                    retention_status,
                    published_generation,
                    next_generation,
                    access_level,
                    expires_at
                ) VALUES (
                    ?,
                    ?,
                    'READY',
                    ?,
                    'ACTIVE',
                    ?,
                    ?,
                    ?,
                    CASE WHEN ? THEN clock_timestamp() - interval '1 second'
                         ELSE NULL
                    END
                )
                """,
                documentId,
                expired ? "TTL" : "PERMANENT",
                generation,
                generation,
                generation + 1,
                accessLevel,
                expired
        );
        insertGeneration(documentId, generation, accessLevel, "PUBLISHED");
        seedProjection(documentId, generation, accessLevel, chunkId);
    }

    private void supersede(
            String documentId,
            long previousGeneration,
            long publishedGeneration,
            long accessLevel
    ) {
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRING',
                    cleanup_required = true,
                    retired_at = clock_timestamp()
                WHERE document_id = ?
                  AND generation = ?
                  AND access_level = ?
                """,
                documentId,
                previousGeneration,
                accessLevel
        );
        insertGeneration(documentId, publishedGeneration, accessLevel, "PUBLISHED");
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET generation = ?,
                    published_generation = ?,
                    next_generation = ?
                WHERE document_id = ?
                  AND access_level = ?
                """,
                publishedGeneration,
                publishedGeneration,
                publishedGeneration + 1,
                documentId,
                accessLevel
        );
    }

    private void insertGeneration(
            String documentId,
            long generation,
            long accessLevel,
            String status
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    generation_kind,
                    physical_id_version,
                    access_level,
                    cleanup_required,
                    started_at,
                    published_at
                ) VALUES (?, ?, ?, 'INGESTION', 2, ?, false,
                          clock_timestamp(), clock_timestamp())
                """,
                documentId,
                generation,
                status,
                accessLevel
        );
    }

    private void seedProjection(
            String documentId,
            long generation,
            long accessLevel,
            String chunkId
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_search_projection (
                    access_level,
                    document_id,
                    generation,
                    chunk_id,
                    chunk_index,
                    text_content,
                    embedding_text,
                    language,
                    domain
                ) VALUES (?, ?, ?, ?, 0, 'evidence', 'evidence', 'en', 'GENERAL')
                """,
                accessLevel,
                documentId,
                generation,
                chunkId
        );
    }
}
