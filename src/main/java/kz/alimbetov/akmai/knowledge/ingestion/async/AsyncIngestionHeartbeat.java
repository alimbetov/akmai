package kz.alimbetov.akmai.knowledge.ingestion.async;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
        prefix = "akmai.ingestion.async-worker",
        name = "enabled",
        havingValue = "true"
)
public class AsyncIngestionHeartbeat {

    private final AsyncIngestionJobRepository repository;
    private final AsyncIngestionProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Map<java.util.UUID, Handle> active = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private ExecutorService renewalExecutor;

    AsyncIngestionHeartbeat(
            AsyncIngestionJobRepository repository,
            AsyncIngestionProperties properties
    ) {
        this(repository, properties, null);
    }

    @Autowired
    public AsyncIngestionHeartbeat(
            AsyncIngestionJobRepository repository,
            AsyncIngestionProperties properties,
            @Qualifier("asyncIngestionHeartbeatTransactionTemplate")
            TransactionTemplate transactionTemplate
    ) {
        this.repository = repository;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    void start() {
        renewalExecutor = Executors.newFixedThreadPool(
                properties.maxConcurrentIngestions(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "async-ingestion-heartbeat-renew"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "async-ingestion-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = properties.heartbeatInterval().toMillis();
        scheduler.scheduleWithFixedDelay(
                this::heartbeatSafely,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (renewalExecutor != null) {
            renewalExecutor.shutdownNow();
        }
    }

    public Handle register(AsyncIngestionClaim claim) {
        Handle handle = new Handle(claim);
        Handle existing = active.putIfAbsent(claim.ingestionId(), handle);
        if (existing != null) {
            throw new IllegalStateException(
                    "Heartbeat already registered for async ingestion "
                            + claim.ingestionId()
            );
        }
        return handle;
    }

    private void heartbeatSafely() {
        for (Handle handle : active.values()) {
            if (handle.closed.get() || handle.ownershipLost.get()) {
                continue;
            }
            if (renewalExecutor == null) {
                renewSafely(handle);
                continue;
            }
            if (!handle.renewalInFlight.compareAndSet(false, true)) {
                continue;
            }
            try {
                renewalExecutor.execute(() -> {
                    try {
                        renewSafely(handle);
                    } finally {
                        handle.renewalInFlight.set(false);
                    }
                });
            } catch (RuntimeException exception) {
                handle.renewalInFlight.set(false);
                handle.ownershipLost.set(true);
            }
        }
    }

    private void renewSafely(Handle handle) {
        if (handle.closed.get() || handle.ownershipLost.get()) {
            return;
        }
        try {
            if (!renewWithDeadline(handle.claim)) {
                handle.ownershipLost.set(true);
            }
        } catch (RuntimeException exception) {
            handle.ownershipLost.set(true);
        }
    }

    private boolean renewWithDeadline(AsyncIngestionClaim claim) {
        if (transactionTemplate == null) {
            return repository.renew(claim, properties.leaseDuration());
        }
        Boolean renewed = transactionTemplate.execute(status ->
                repository.renew(claim, properties.leaseDuration())
        );
        return Boolean.TRUE.equals(renewed);
    }

    public final class Handle implements AutoCloseable {
        private final AsyncIngestionClaim claim;
        private final AtomicBoolean ownershipLost = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean renewalInFlight = new AtomicBoolean();

        private Handle(AsyncIngestionClaim claim) {
            this.claim = claim;
        }

        public boolean ownershipLost() {
            return ownershipLost.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                active.remove(claim.ingestionId(), this);
            }
        }
    }
}
