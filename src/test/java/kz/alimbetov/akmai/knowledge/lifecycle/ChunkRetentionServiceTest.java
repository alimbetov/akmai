package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
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
class ChunkRetentionServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

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
        tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void staleClaimCannotReachAnyDestructiveRepository() {
        insertLifecycleWithDifferentClaim();

        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        SearchProjectionRepository projections =
                mock(PostgresSearchProjectionRepository.class);
        DocumentIdentifierRepository identifiers =
                mock(DocumentIdentifierRepository.class);
        VectorGenerationRepository manifests =
                mock(VectorGenerationRepository.class);
        PostgresGenerationVectorRepository vectors =
                mock(PostgresGenerationVectorRepository.class);
        EmbeddingProfileRepository profiles =
                mock(EmbeddingProfileRepository.class);

        ChunkRetentionService service = new ChunkRetentionService(
                jdbc,
                tx,
                claims,
                projections,
                identifiers,
                manifests,
                vectors,
                profiles
        );

        RetentionClaim stale = new RetentionClaim(
                "doc-1",
                7,
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000007"
                ),
                "pod-a",
                Instant.now().plus(Duration.ofMinutes(10))
        );

        RetentionCleanupResult result = service.cleanup(stale);

        assertThat(result.status())
                .isEqualTo(RetentionCleanupResult.Status.STALE_CLAIM);
        verify(vectors, never()).deleteIds(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
        verify(projections, never()).deleteGeneration("doc-1", 7);
        verify(identifiers, never()).deleteGeneration("doc-1", 7);
        verify(manifests, never()).deleteGeneration("doc-1", 7);
    }

    private void insertLifecycleWithDifferentClaim() {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, claim_generation, claim_id, claimed_by,
                    claimed_at, lease_until, expires_at, delete_started_at,
                    deleted_at, attempt_count, last_error, row_version,
                    ingestion_started_at, created_at, updated_at,
                    retention_status, published_generation, next_generation
                ) VALUES (
                    'doc-1', 'TTL', 'DELETE_PENDING',
                    7, 7, ?::uuid, 'pod-b',
                    clock_timestamp(), clock_timestamp() + interval '10 minutes',
                    clock_timestamp() - interval '1 minute', NULL,
                    NULL, 0, NULL, 0,
                    NULL, clock_timestamp(), clock_timestamp(),
                    'DELETE_PENDING', 7, 8
                )
                """,
                "00000000-0000-0000-0000-000000000099"
        );
    }
}
