package kz.alimbetov.akmai.knowledge.lifecycle;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    private final java.util.concurrent.ConcurrentMap<UUID, AtomicBoolean> lostLeases =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        int requested = Math.min(properties.batchSize(), availableCapacity());
        int permits = reserveUpTo(requested);
        if (permits == 0) {
            return 0;
        }
        List<RetentionClaim> claims;
        try {
            claims = lifecycleRepository.claimExpired(
                clock.instant(),
                permits,
                properties.retryLimit(),
                workerId,
                properties.leaseDuration()
            );
        } catch (RuntimeException exception) {
            reserved.addAndGet(-permits);
            throw exception;
        }

        reserved.addAndGet(-(permits - claims.size()));
        int submitted = 0;
        for (RetentionClaim claim : claims) {
            try {
                workers.execute(() -> runClaim(claim));
                submitted++;
            } catch (RuntimeException exception) {
                reserved.decrementAndGet();
                lifecycleRepository.releaseClaim(claim, clock.instant());
            }
        }
        return submitted;
    }

    private int reserveUpTo(int requested) {
        if (requested <= 0) {
            return 0;
        }
        while (accepting.get()) {
            int current = reserved.get();
            int maximum = properties.workerParallelism() + properties.queueCapacity();
            int available = maximum - current;
            if (available <= 0) {
                return 0;
            }
            int granted = Math.min(requested, available);
            if (reserved.compareAndSet(current, current + granted)) {
                return granted;
            }
        }
        return 0;
    }

    private void runClaim(RetentionClaim claim) {
        AtomicBoolean lostLease = new AtomicBoolean(false);
        lostLeases.put(claim.claimId(), lostLease);
        ScheduledFuture<?> heartbeat = startHeartbeat(claim, lostLease);
        try {
            if (!lostLease.get()) {
                cleanupService.cleanup(claim);
            }
        } finally {
            heartbeat.cancel(false);
            lostLeases.remove(claim.claimId());
            reserved.decrementAndGet();
        }
    }

    private ScheduledFuture<?> startHeartbeat(RetentionClaim claim, AtomicBoolean lostLease) {
        long periodMillis = heartbeatPeriod(properties.leaseDuration()).toMillis();
        return heartbeatExecutor.scheduleAtFixedRate(
                () -> renewSafely(claim, lostLease),
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private void renewSafely(RetentionClaim claim, AtomicBoolean lostLease) {
        try {
            if (!lifecycleRepository.renewLease(
                    claim,
                    Instant.now(clock),
                    properties.leaseDuration()
            )) {
                lostLease.set(true);
            }
        } catch (RuntimeException exception) {
            lostLease.set(true);
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
