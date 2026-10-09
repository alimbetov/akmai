package kz.alimbetov.akmai.knowledge.ingestion.async;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import org.springframework.stereotype.Component;

@Component
public class AsyncIngestionHeartbeat {

    private final AsyncIngestionJobRepository repository;
    private final AsyncIngestionProperties properties;
    private final Map<java.util.UUID, Handle> active = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    public AsyncIngestionHeartbeat(
            AsyncIngestionJobRepository repository,
            AsyncIngestionProperties properties
    ) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "async-ingestion-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = properties.heartbeatInterval().toMillis();
        executor.scheduleWithFixedDelay(
                this::heartbeatSafely,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
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
            try {
                if (!repository.renew(handle.claim, properties.leaseDuration())) {
                    handle.ownershipLost.set(true);
                }
            } catch (RuntimeException exception) {
                handle.ownershipLost.set(true);
            }
        }
    }

    public final class Handle implements AutoCloseable {
        private final AsyncIngestionClaim claim;
        private final AtomicBoolean ownershipLost = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

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
