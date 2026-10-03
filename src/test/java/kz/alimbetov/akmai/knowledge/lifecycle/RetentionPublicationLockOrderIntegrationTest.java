package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
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
class RetentionPublicationLockOrderIntegrationTest {

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
    static ExecutorService executor;

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
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");

        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id,
                    lifecycle_policy,
                    lifecycle_status,
                    generation,
                    attempt_count,
                    row_version,
                    retention_status,
                    published_generation,
                    next_generation,
                    access_level,
                    created_at,
                    updated_at
                ) VALUES (
                    'doc-lock',
                    'PERMANENT',
                    'INGESTING',
                    1,
                    0,
                    0,
                    'ACTIVE',
                    NULL,
                    2,
                    1,
                    clock_timestamp(),
                    clock_timestamp()
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    access_level
                ) VALUES (
                    'doc-lock',
                    1,
                    'STAGING',
                    1
                )
                """
        );
    }

    @Test
    void retentionFirstDoesNotDeadlockPublicationOrder() throws Exception {
        CountDownLatch retentionHasLifecycle = new CountDownLatch(1);
        CountDownLatch publicationHasRuntime = new CountDownLatch(1);
        CountDownLatch releaseRetention = new CountDownLatch(1);

        Future<?> retention = executor.submit(() -> tx.execute(status -> {
            lockLifecycle();
            retentionHasLifecycle.countDown();
            await(releaseRetention);
            lockGeneration();
            return null;
        }));

        Future<?> publication = executor.submit(() -> {
            await(retentionHasLifecycle);
            return tx.execute(status -> {
                lockRuntime();
                publicationHasRuntime.countDown();
                lockLifecycle();
                lockGeneration();
                return null;
            });
        });

        assertThat(publicationHasRuntime.await(5, TimeUnit.SECONDS))
                .isTrue();
        releaseRetention.countDown();

        retention.get(5, TimeUnit.SECONDS);
        publication.get(5, TimeUnit.SECONDS);
    }

    @Test
    void publicationFirstDoesNotDeadlockRetentionOrder() throws Exception {
        CountDownLatch publicationHasLifecycle = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);

        Future<?> publication = executor.submit(() -> tx.execute(status -> {
            lockRuntime();
            lockLifecycle();
            publicationHasLifecycle.countDown();
            await(releasePublication);
            lockGeneration();
            return null;
        }));

        Future<?> retention = executor.submit(() -> {
            await(publicationHasLifecycle);
            return tx.execute(status -> {
                lockLifecycle();
                lockGeneration();
                return null;
            });
        });

        releasePublication.countDown();

        publication.get(5, TimeUnit.SECONDS);
        retention.get(5, TimeUnit.SECONDS);
    }

    private static void lockRuntime() {
        jdbc.queryForObject(
                """
                SELECT singleton_id
                FROM knowledge_embedding_runtime
                WHERE singleton_id = 1
                FOR UPDATE
                """,
                Integer.class
        );
    }

    private static void lockLifecycle() {
        jdbc.queryForObject(
                """
                SELECT generation
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-lock'
                FOR UPDATE
                """,
                Long.class
        );
    }

    private static void lockGeneration() {
        jdbc.queryForObject(
                """
                SELECT generation
                FROM knowledge_document_generation
                WHERE document_id = 'doc-lock'
                  AND generation = 1
                FOR UPDATE
                """,
                Long.class
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for lock step");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", exception);
        }
    }
}
