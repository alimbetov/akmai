package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DocumentOperationLockIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    @Test
    void tryAcquireRejectsSameDocumentWhileSessionLockIsHeld() {
        DocumentOperationLock lock = new DocumentOperationLock(dataSource());

        try (var first = lock.acquire("doc-race")) {
            assertThat(lock.tryAcquire("doc-race")).isEmpty();
            assertThat(lock.tryAcquire("doc-other")).isPresent().hasValueSatisfying(handle -> {
                try (handle) {
                    // Different documents remain independently available.
                }
            });
        }

        assertThat(lock.tryAcquire("doc-race")).isPresent().hasValueSatisfying(handle -> {
            try (handle) {
                // Releasing the first session makes the document recoverable.
            }
        });
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
