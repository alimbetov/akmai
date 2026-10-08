package kz.alimbetov.akmai.knowledge.graph;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a fresh, bounded transaction for graph mutation work.
 * The timeout is applied to the actual Spring transaction rather than to a
 * surrounding coordinator/run scope.
 */
@Component
public class GraphTransactionExecutor {

    private final PlatformTransactionManager transactionManager;

    public GraphTransactionExecutor(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public <T> T execute(Duration timeout, Supplier<T> action) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("graph transaction timeout must be positive");
        }
        if (action == null) {
            throw new IllegalArgumentException("graph transaction action must not be null");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(timeoutSeconds(timeout));
        return transaction.execute(status -> action.get());
    }

    private static int timeoutSeconds(Duration timeout) {
        long seconds = Math.max(1L, (timeout.toMillis() + 999L) / 1000L);
        return Math.toIntExact(Math.min(seconds, Integer.MAX_VALUE));
    }
}
