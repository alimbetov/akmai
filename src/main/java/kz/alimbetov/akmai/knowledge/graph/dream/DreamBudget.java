package kz.alimbetov.akmai.knowledge.graph.dream;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe run budget. Capacity is reserved before performing work. */
public final class DreamBudget {

    private final int maxSources;
    private final int maxAnnQueries;
    private final int maxReverseAnnQueries;
    private final long maxDbRowsTouched;
    private final long deadlineNanos;
    private final AtomicInteger sources = new AtomicInteger();
    private final AtomicInteger annQueries = new AtomicInteger();
    private final AtomicInteger reverseAnnQueries = new AtomicInteger();
    private final AtomicLong dbRowsTouched = new AtomicLong();

    public DreamBudget(
            int maxSources,
            int maxAnnQueries,
            int maxReverseAnnQueries,
            long maxDbRowsTouched,
            Duration maxDuration
    ) {
        if (maxSources <= 0 || maxAnnQueries <= 0
                || maxReverseAnnQueries <= 0 || maxDbRowsTouched <= 0
                || maxDuration == null || maxDuration.isZero()
                || maxDuration.isNegative()) {
            throw new IllegalArgumentException("Dream budgets must be positive");
        }
        this.maxSources = maxSources;
        this.maxAnnQueries = maxAnnQueries;
        this.maxReverseAnnQueries = maxReverseAnnQueries;
        this.maxDbRowsTouched = maxDbRowsTouched;
        this.deadlineNanos = System.nanoTime() + maxDuration.toNanos();
    }

    public void acquireSource() {
        requireTime();
        if (sources.incrementAndGet() > maxSources) {
            sources.decrementAndGet();
            throw new BudgetExhaustedException(StopReason.MAX_SOURCES);
        }
    }

    public void acquireForwardAnn() {
        acquireAnn(false);
    }

    public void acquireReverseAnn() {
        acquireAnn(true);
    }

    private void acquireAnn(boolean reverse) {
        requireTime();
        if (annQueries.incrementAndGet() > maxAnnQueries) {
            annQueries.decrementAndGet();
            throw new BudgetExhaustedException(StopReason.MAX_ANN_QUERIES);
        }
        if (reverse && reverseAnnQueries.incrementAndGet() > maxReverseAnnQueries) {
            reverseAnnQueries.decrementAndGet();
            annQueries.decrementAndGet();
            throw new BudgetExhaustedException(StopReason.MAX_REVERSE_ANN_QUERIES);
        }
    }

    public void addDbRows(long rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must not be negative");
        }
        requireTime();
        long total = dbRowsTouched.addAndGet(rows);
        if (total > maxDbRowsTouched) {
            dbRowsTouched.addAndGet(-rows);
            throw new BudgetExhaustedException(StopReason.MAX_DB_ROWS);
        }
    }

    public void requireTime() {
        if (System.nanoTime() >= deadlineNanos) {
            throw new BudgetExhaustedException(StopReason.MAX_RUN_DURATION);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                sources.get(),
                annQueries.get(),
                reverseAnnQueries.get(),
                dbRowsTouched.get()
        );
    }

    public enum StopReason {
        MAX_SOURCES,
        MAX_ANN_QUERIES,
        MAX_REVERSE_ANN_QUERIES,
        MAX_DB_ROWS,
        MAX_RUN_DURATION
    }

    public static final class BudgetExhaustedException
            extends RuntimeException {
        private final StopReason reason;

        public BudgetExhaustedException(StopReason reason) {
            super("Dream budget exhausted: " + reason);
            this.reason = reason;
        }

        public StopReason reason() {
            return reason;
        }
    }

    public record Snapshot(
            int sources,
            int annQueries,
            int reverseAnnQueries,
            long dbRowsTouched
    ) {
    }
}
