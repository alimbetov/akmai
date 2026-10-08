package kz.alimbetov.akmai.knowledge.graph;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

/** Shared JDBC timeout conversion for graph and semantic queries. */
public final class JdbcTimeouts {

    private JdbcTimeouts() {
    }

    public static void applyQueryTimeout(
            PreparedStatement statement,
            Duration timeout
    ) throws SQLException {
        if (timeout == null) {
            return;
        }
        statement.setQueryTimeout(toSeconds(timeout));
    }

    public static int toSeconds(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long millis = timeout.toMillis();
        long seconds = Math.max(1L, (millis + 999L) / 1000L);
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }
}
