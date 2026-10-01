package kz.alimbetov.akmai.knowledge.lifecycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class DocumentOperationLock {

    private final DataSource dataSource;

    public DocumentOperationLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public LockHandle acquire(String documentId) {
        try {
            Connection connection = dataSource.getConnection();
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
                statement.setString(1, documentId);
                statement.execute();
            } catch (RuntimeException | SQLException exception) {
                connection.close();
                throw exception;
            }
            return new LockHandle(connection, documentId);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot acquire document operation lock for " + documentId,
                    exception
            );
        }
    }

    public static final class LockHandle implements AutoCloseable {

        private final Connection connection;
        private final String documentId;
        private boolean closed;

        private LockHandle(Connection connection, String documentId) {
            this.connection = connection;
            this.documentId = documentId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT pg_advisory_unlock(hashtextextended(?, 0))"
                )) {
                    statement.setString(1, documentId);
                    statement.execute();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Cannot release document operation lock for " + documentId,
                        exception
                );
            } finally {
                closed = true;
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // The lock is session-scoped and is released when the connection closes.
                }
            }
        }
    }
}
