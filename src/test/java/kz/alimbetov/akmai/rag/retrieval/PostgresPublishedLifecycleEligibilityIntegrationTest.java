package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
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
class PostgresPublishedLifecycleEligibilityIntegrationTest {

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
    static PostgresPublishedLifecycleEligibility eligibility;

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
        eligibility = new PostgresPublishedLifecycleEligibility(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void expiredActivePublishedRowIsRejectedForEveryRetrievalType() {
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
        insertPublished("expired-doc", "TTL", expiredAt, 1L, 1L);

        List<RetrievalHit> hits = Arrays.stream(RetrievalType.values())
                .map(type -> hit(type, 1L, "expired-doc", 1L))
                .toList();

        assertThat(eligibility.filter(hits, Set.of(1L))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT retention_status = 'ACTIVE' AND expires_at < clock_timestamp() FROM knowledge_document_lifecycle WHERE document_id = ?",
                Boolean.class,
                "expired-doc"
        )).isTrue();
    }

    @Test
    void finalContextRevalidationRejectsDocumentThatExpiresAfterRetrieval() {
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        insertPublished("race-doc", "TTL", future, 1L, 1L);
        RetrievalHit retrieved = hit(RetrievalType.VECTOR, 1L, "race-doc", 1L);

        PublishedSearchProjectionReader projectionReader =
                mock(PublishedSearchProjectionReader.class);
        when(projectionReader.findPublishedByKeys(anyList(), anySet()))
                .thenReturn(List.of(projection(
                        "race-doc",
                        retrieved.chunkId(),
                        1L,
                        1L
                )));
        PublishedContextRevalidator revalidator = new PublishedContextRevalidator(
                projectionReader,
                eligibility
        );

        jdbc.update(
                "UPDATE knowledge_document_lifecycle SET expires_at = clock_timestamp() - interval '1 second' WHERE document_id = ?",
                "race-doc"
        );

        assertThat(revalidator.revalidate(List.of(retrieved), Set.of(1L)))
                .isEmpty();
    }

    @Test
    void finalContextRevalidationRejectsPublishedGenerationWhileLifecycleIsIngesting() {
        insertPublished("ingesting-doc", "PERMANENT", null, 7L, 1L);
        RetrievalHit retrieved = hit(
                RetrievalType.VECTOR,
                1L,
                "ingesting-doc",
                7L
        );

        PublishedSearchProjectionReader projectionReader =
                mock(PublishedSearchProjectionReader.class);
        when(projectionReader.findPublishedByKeys(anyList(), anySet()))
                .thenReturn(List.of(projection(
                        "ingesting-doc",
                        retrieved.chunkId(),
                        7L,
                        1L
                )));
        PublishedContextRevalidator revalidator = new PublishedContextRevalidator(
                projectionReader,
                eligibility
        );

        jdbc.update(
                "UPDATE knowledge_document_lifecycle SET lifecycle_status = 'INGESTING', generation = 8, next_generation = 9 WHERE document_id = ?",
                "ingesting-doc"
        );

        assertThat(revalidator.revalidate(List.of(retrieved), Set.of(1L)))
                .isEmpty();
    }

    @Test
    void lifecyclePointingAtNonPublishedGenerationFailsClosed() {
        insertPublished("inconsistent-doc", "PERMANENT", null, 3L, 1L);
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRING',
                    retired_at = clock_timestamp()
                WHERE document_id = ?
                  AND generation = ?
                """,
                "inconsistent-doc",
                3L
        );

        assertThat(eligibility.filter(
                List.of(hit(RetrievalType.VECTOR, 1L, "inconsistent-doc", 3L)),
                Set.of(1L)
        )).isEmpty();
    }

    @Test
    void nonExpiredRowsRemainEligible() {
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        insertPublished("future-ttl", "TTL", future, 1L, 1L);
        insertPublished("permanent", "PERMANENT", null, 1L, 1L);

        List<RetrievalHit> hits = List.of(
                hit(RetrievalType.VECTOR, 1L, "future-ttl", 1L),
                hit(RetrievalType.LEXICAL, 1L, "permanent", 1L)
        );

        assertThat(eligibility.filter(hits, Set.of(1L)))
                .extracting(RetrievalHit::documentId)
                .containsExactly("future-ttl", "permanent");
    }

    @Test
    void publishedGenerationAndAclRemainPartOfTheFence() {
        insertPublished("published-doc", "PERMANENT", null, 2L, 1L);
        insertPublished("other-acl", "PERMANENT", null, 1L, 2L);

        List<RetrievalHit> hits = List.of(
                hit(RetrievalType.VECTOR, 1L, "published-doc", 2L),
                hit(RetrievalType.LEXICAL, 1L, "published-doc", 1L),
                hit(RetrievalType.IDENTIFIER, 2L, "other-acl", 1L)
        );

        assertThat(eligibility.filter(hits, Set.of(1L)))
                .extracting(RetrievalHit::documentId, RetrievalHit::generation)
                .containsExactly(tuple("published-doc", 2L));
    }

    private void insertPublished(
            String documentId,
            String policy,
            OffsetDateTime expiresAt,
            long publishedGeneration,
            long accessLevel
    ) {
        jdbc.update(
                "INSERT INTO knowledge_document_lifecycle (document_id, lifecycle_policy, lifecycle_status, generation, retention_status, published_generation, next_generation, access_level, expires_at) VALUES (?, ?, 'READY', ?, 'ACTIVE', ?, ?, ?, ?)",
                documentId,
                policy,
                publishedGeneration,
                publishedGeneration,
                publishedGeneration + 1,
                accessLevel,
                expiresAt
        );
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
                ) VALUES (?, ?, 'PUBLISHED', 'INGESTION', 2, ?, false,
                          clock_timestamp(), clock_timestamp())
                """,
                documentId,
                publishedGeneration,
                accessLevel
        );
    }

    private RetrievalHit hit(
            RetrievalType type,
            long accessLevel,
            String documentId,
            long generation
    ) {
        return new RetrievalHit(
                type,
                accessLevel,
                documentId,
                generation,
                type.name().toLowerCase() + "-chunk",
                "evidence",
                Map.of()
        );
    }

    private SearchProjection projection(
            String documentId,
            String chunkId,
            long generation,
            long accessLevel
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                generation,
                accessLevel,
                null,
                0,
                "evidence",
                "evidence",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of(),
                2
        );
    }
}
