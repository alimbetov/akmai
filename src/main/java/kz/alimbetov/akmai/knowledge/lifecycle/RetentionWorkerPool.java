package kz.alimbetov.akmai.knowledge.lifecycle;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class RetentionWorkerPool {

    private final DocumentLifecycleRepository lifecycleRepository;
    private final ChunkRetentionService cleanupService;
    private final RetentionProperties properties;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger reserved = new AtomicInteger();

    public RetentionWorkerPool(
            DocumentLifecycleRepository lifecycleRepository,
            ChunkRetentionService cleanupService,
            RetentionProperties properties
    ) {
        this(lifecycleRepository, cleanupService, properties, Clock.systemUTC());
    }

    RetentionWorkerPool(
            DocumentLifecycleRepository lifecycleRepository,
            ChunkRetentionService cleanupService,
            RetentionProperties properties,
            Clock clock
    ) {
        this.lifecycleRepository = lifecycleRepository;
        this.cleanupService = cleanupService;
        this.properties = properties;
        this.clock = clock;
        this.workers = new ThreadPoolExecutor(
                properties.workerParallelism(),
                properties.workerParallelism(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                new ThreadPoolExecutor.AbortPolicy()
        );
        ScheduledThreadPoolExecutor heartbeat = new ScheduledThreadPoolExecutor(1);
        heartbeat.setRemoveOnCancelPolicy(true);
        this.heartbeatExecutor = heartbeat;
    }

    public int availableCapacity() {
        return Math.max(
                0,
                properties.workerParallelism() + properties.queueCapacity() - reserved.get()
        );
    }

    public int claimAndSubmit(String workerId) {
        if (!accepting.get()) {
            return 0;
        }
        int capacity = availableCapacity();
        if (capacity == 0) {
            return 0;
        }
        int claimSize = Math.min(properties.batchSize(), capacity);
        List<RetentionClaim> claims = lifecycleRepository.claimExpired(
                clock.instant(),
                claimSize,
                properties.retryLimit(),
                workerId,
                properties.leaseDuration()
        );
        int submitted = 0;
        for (RetentionClaim claim : claims) {
            if (!reserve()) {
                break;
            }
            try {
                workers.execute(() -> runClaim(claim));
                submitted++;
            } catch (RuntimeException exception) {
                reserved.decrementAndGet();
                throw exception;
            }
        }
        return submitted;
    }

    private boolean reserve() {
        while (accepting.get()) {
            int current = reserved.get();
            int maximum = properties.workerParallelism() + properties.queueCapacity();
            if (current >= maximum) {
                return false;
            }
            if (reserved.compareAndSet(current, current + 1)) {
                return true;
            }
        }
        return false;
    }

    private void runClaim(RetentionClaim claim) {
        ScheduledFuture<?> heartbeat = startHeartbeat(claim);
        try {
            cleanupService.cleanup(claim);
        } finally {
            heartbeat.cancel(false);
            reserved.decrementAndGet();
        }
    }

    private ScheduledFuture<?> startHeartbeat(RetentionClaim claim) {
        long periodMillis = heartbeatPeriod(properties.leaseDuration()).toMillis();
        return heartbeatExecutor.scheduleAtFixedRate(
                () -> renewSafely(claim),
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private void renewSafely(RetentionClaim claim) {
        try {
            lifecycleRepository.renewLease(
                    claim,
                    Instant.now(clock),
                    properties.leaseDuration()
            );
        } catch (RuntimeException ignored) {
            // Cleanup performs claim checks around destructive boundaries.
        }
    }

    static Duration heartbeatPeriod(Duration leaseDuration) {
        Duration period = leaseDuration.dividedBy(3);
        return period.isZero() ? Duration.ofMillis(1) : period;
    }

    @PreDestroy
    public void shutdown() {
        accepting.set(false);
        workers.shutdown();
        heartbeatExecutor.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
            if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
            heartbeatExecutor.shutdownNow();
        }
    }
}
