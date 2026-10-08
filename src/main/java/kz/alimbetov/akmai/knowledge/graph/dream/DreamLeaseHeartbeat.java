package kz.alimbetov.akmai.knowledge.graph.dream;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dedicated heartbeat executor. It must never share the ANN/worker executor so
 * ANN saturation cannot cause a healthy Dream owner to lose its lease.
 */
@Component
public class DreamLeaseHeartbeat {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DreamLeaseHeartbeat.class);

    private final DreamLeaseManager leases;
    private final Duration interval;
    private final ScheduledExecutorService executor;

    public DreamLeaseHeartbeat(
            DreamLeaseManager leases,
            AdaptiveGraphProperties properties
    ) {
        this.leases = leases;
        this.interval = properties.dream().heartbeatInterval();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "akmai-dream-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public Session start(
            DreamLeaseManager.Authority authority,
            Runnable onAuthorityLost
    ) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(onAuthorityLost, "onAuthorityLost");
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        Runnable task = () -> {
            if (!active.get()) {
                return;
            }
            try {
                leases.renew(authority);
            } catch (RuntimeException exception) {
                if (active.compareAndSet(true, false)) {
                    LOGGER.warn(
                            "dream_lease event=heartbeat_lost graphVersion={} token={} errorType={}",
                            authority.graphVersion(),
                            authority.fencingToken(),
                            exception.getClass().getSimpleName()
                    );
                    try {
                        onAuthorityLost.run();
                    } finally {
                        ScheduledFuture<?> future = futureRef.get();
                        if (future != null) {
                            future.cancel(false);
                        }
                    }
                }
            }
        };

        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                task,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        futureRef.set(future);
        return new Session(active, future);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public static final class Session implements AutoCloseable {
        private final AtomicBoolean active;
        private final ScheduledFuture<?> future;

        private Session(
                AtomicBoolean active,
                ScheduledFuture<?> future
        ) {
            this.active = active;
            this.future = future;
        }

        public boolean active() {
            return active.get();
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                future.cancel(false);
            }
        }
    }
}
