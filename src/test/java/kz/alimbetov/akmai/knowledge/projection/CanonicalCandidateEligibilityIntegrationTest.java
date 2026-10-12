package kz.alimbetov.akmai.knowledge.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class CanonicalCandidateEligibilityIntegrationTest {

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
    static CanonicalPostgresSearchProjectionRepository projections;
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
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
        projections = new CanonicalPostgresSearchProjectionRepository(
                jdbc,
                new ObjectMapper()
        );
        identifiers = new DocumentIdentifierRepository(jdbc);
        generations = new DocumentGenerationRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
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
    void lexicalLimitIgnoresHigherRankedIngestingCandidate() {
        long staleGeneration = generation("a-stale", "fp-stale");
        long goodGeneration = generation("z-good", "fp-good");
        projections.saveAll(List.of(
                projection(
                        "a-stale",
                        "stale",
                        staleGeneration,
                        "critical payment policy critical payment policy"
                ),
                projection(
                        "z-good",
                        "good",
                        goodGeneration,
                        "critical payment policy"
                )
        ));
        publish("a-stale", staleGeneration);
        publish("z-good", goodGeneration);
        lifecycleStatus("a-stale", "INGESTING");

        assertThat(projections.searchLexical(
                "critical payment policy",
                "en",
                List.of("a-stale", "z-good"),
                Set.of(1L),
                1
        )).extracting(SearchProjection::chunkId)
                .containsExactly("good");
    }

    @Test
    void conceptLimitIgnoresFirstIneligibleCandidate() {
        String concept = "finance_banking.lending_credit.corporate_credit_facility";
        long staleGeneration = generation("a-stale", "fp-stale");
        long goodGeneration = generation("z-good", "fp-good");
        projections.saveAll(List.of(
                projection(
                        "a-stale",
                        "stale",
                        staleGeneration,
                        "corporate credit",
                        Map.of("semanticConcepts", List.of(concept))
                ),
                projection(
                        "z-good",
                        "good",
                        goodGeneration,
                        "corporate credit",
                        Map.of("semanticConcepts", List.of(concept))
                )
        ));
        publish("a-stale", staleGeneration);
        publish("z-good", goodGeneration);
        lifecycleStatus("a-stale", "FAILED");

        assertThat(projections.searchSemanticConcepts(
                List.of(concept),
                List.of("a-stale", "z-good"),
                Set.of(1L),
                1
        )).extracting(SearchProjection::chunkId)
                .containsExactly("good");
    }

    @Test
    void identifierLimitIgnoresNewerIneligibleCandidate() {
        long goodGeneration = generation("good-doc", "fp-good");
        long staleGeneration = generation("stale-doc", "fp-stale");
        identifiers.saveAll(List.of(
                identifier(
                        "good-doc",
                        goodGeneration,
                        "good",
                        Instant.parse("2026-10-01T00:00:00Z")
                ),
                identifier(
                        "stale-doc",
                        staleGeneration,
                        "stale",
                        Instant.parse("2026-10-02T00:00:00Z")
                )
        ));
        publish("good-doc", goodGeneration);
        publish("stale-doc", staleGeneration);
        lifecycleStatus("stale-doc", "INGESTING");

        assertThat(identifiers.findExact(
                IdentifierType.DOCUMENT_NUMBER,
                "DOC-42",
                Set.of(1L),
                1
        )).extracting(DocumentIdentifier::chunkId)
                .containsExactly("good");
    }

    @Test
    void keyedLookupBatchesMoreThan256KeysAndPreservesFirstOccurrenceOrder() {
        long generation = generation("doc", "fp-batch");
        ArrayList<SearchProjection> rows = new ArrayList<>();
        ArrayList<PublishedSearchProjectionReader.ProjectionKey> keys = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            String chunkId = "chunk-" + index;
            rows.add(projection("doc", chunkId, generation, "text " + index));
            keys.add(new PublishedSearchProjectionReader.ProjectionKey(
                    1L,
                    "doc",
                    generation,
                    chunkId
            ));
        }
        projections.saveAll(rows);
        publish("doc", generation);
        keys.add(keys.getFirst());

        List<SearchProjection> result = projections.findPublishedByKeys(
                keys,
                Set.of(1L)
        );

        assertThat(result).hasSize(257);
        assertThat(result.getFirst().chunkId()).isEqualTo("chunk-0");
        assertThat(result.getLast().chunkId()).isEqualTo("chunk-256");
    }

    @Test
    void keyedLookupRejectsExpiredLifecycleSynchronously() {
        long generation = generations.allocate(
                "expired-doc",
                RetentionPolicy.TEMPORARY,
                Instant.parse("2026-10-01T00:00:00Z"),
                null,
                "fp-expired",
                1L
        );
        projections.saveAll(List.of(projection(
                "expired-doc",
                "expired",
                generation,
                "expired text"
        )));
        publish("expired-doc", generation);
        jdbc.update(
                "UPDATE knowledge_document_lifecycle SET expires_at = clock_timestamp() - interval '1 second' WHERE document_id = ?",
                "expired-doc"
        );

        assertThat(projections.findPublishedByKeys(
                List.of(new PublishedSearchProjectionReader.ProjectionKey(
                        1L,
                        "expired-doc",
                        generation,
                        "expired"
                )),
                Set.of(1L)
        )).isEmpty();
    }

    private long generation(String documentId, String fingerprint) {
        return generations.allocate(
                documentId,
                RetentionPolicy.PERMANENT,
                null,
                null,
                fingerprint,
                1L
        );
    }

    private void publish(String documentId, long generation) {
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

    private void lifecycleStatus(String documentId, String status) {
        jdbc.update(
                "UPDATE knowledge_document_lifecycle SET lifecycle_status = ? WHERE document_id = ?",
                status,
                documentId
        );
    }

    private SearchProjection projection(
            String documentId,
            String chunkId,
            long generation,
            String text
    ) {
        return projection(
                documentId,
                chunkId,
                generation,
                text,
                Map.of("source", "integration")
        );
    }

    private SearchProjection projection(
            String documentId,
            String chunkId,
            long generation,
            String text,
            Map<String, Object> metadata
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                generation,
                null,
                Math.floorMod(chunkId.hashCode(), 1_000_000),
                text,
                text,
                "en",
                KnowledgeDomain.GENERAL,
                "integration",
                List.of(),
                List.of(),
                metadata,
                2
        );
    }

    private DocumentIdentifier identifier(
            String documentId,
            long generation,
            String chunkId,
            Instant createdAt
    ) {
        return new DocumentIdentifier(
                documentId,
                generation,
                chunkId,
                1,
                IdentifierType.DOCUMENT_NUMBER,
                "DOC-42",
                "DOC-42",
                "context",
                createdAt
        );
    }
}
