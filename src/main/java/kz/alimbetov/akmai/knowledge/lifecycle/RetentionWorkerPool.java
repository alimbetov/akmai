package kz.alimbetov.akmai.knowledge.lifecycle;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RetentionWorkerPool {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RetentionWorkerPool.class);

    private final RetentionClaimRepository claimRepository;
    private final ChunkRetentionService cleanupService;
    private final RetentionProperties properties;
    private final ThreadPoolExecutor workers;
    private final Semaphore permits;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Object drainMonitor = new Object();
    private DrainRun activeDrain;
    private AkmaiMetrics metrics;

    public RetentionWorkerPool(
            RetentionClaimRepository claimRepository,
            ChunkRetentionService cleanupService,
            RetentionProperties properties
    ) {
        this.claimRepository = claimRepository;
        this.cleanupService = cleanupService;
        this.properties = properties;
        this.permits = new Semaphore(properties.workerParallelism());
        this.workers = new ThreadPoolExecutor(
                properties.workerParallelism(),
                properties.workerParallelism(),
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Autowired(required = false)
    void setMetrics(AkmaiMetrics metrics) {
        this.metrics = metrics;
    }

    public int availableCapacity() {
        return accepting.get() ? permits.availablePermits() : 0;
    }

    public long backlogCount() {
        return claimRepository.countEligibleBacklog(properties.retryLimit());
    }

    public int claimAndSubmit(String workerId) {
        return claimAndSubmit(
                workerId,
                properties.workerParallelism(),
                null
        );
    }

    public int drain(String workerId, int maxClaims) {
        if (maxClaims <= 0 || !accepting.get()) {
            return 0;
        }

        DrainRun run;
        synchronized (drainMonitor) {
            if (activeDrain == null) {
                activeDrain = new DrainRun(workerId, maxClaims);
            }
            run = activeDrain;
        }

        int submitted = refill(run);
        completeDrainIfDone(run);
        return submitted;
    }

    private int refill(DrainRun run) {
        synchronized (run) {
            if (!accepting.get()
                    || run.exhausted
                    || run.remainingClaims <= 0) {
                return 0;
            }

            int submitted = 0;
            while (accepting.get()
                    && !run.exhausted
                    && run.remainingClaims > 0) {
                int requested = Math.min(
                        Math.min(
                                properties.batchSize(),
                                properties.workerParallelism()
                        ),
                        run.remainingClaims
                );
                int acquired = acquireUpTo(requested);
                if (acquired == 0) {
                    break;
                }

                List<RetentionClaim> claims;
                try {
                    claims = claimRepository.claimExpired(
                            acquired,
                            properties.retryLimit(),
                            run.workerId,
                            properties.leaseDuration()
                    );
                } catch (RuntimeException exception) {
                    permits.release(acquired);
                    throw exception;
                }

                if (metrics != null) {
                    metrics.retentionClaimed(claims.size());
                }
                LOGGER.info(
                        "retention_claim event=claimed count={} capacity={}",
                        claims.size(),
                        acquired
                );

                int unused = acquired - claims.size();
                if (unused > 0) {
                    permits.release(unused);
                }
                if (claims.isEmpty()) {
                    run.exhausted = true;
                    break;
                }

                for (RetentionClaim claim : claims) {
                    try {
                        run.remainingClaims--;
                        run.activeTasks++;
                        workers.execute(() -> runClaim(claim, run));
                        submitted++;
                    } catch (RuntimeException exception) {
                        run.remainingClaims++;
                        run.activeTasks--;
                        boolean released = claimRepository.release(claim);
                        LOGGER.warn(
                                "retention_claim event=submission_rejected generation={} released={}",
                                claim.generation(),
                                released
                        );
                        permits.release();
                    }
                }
            }
            return submitted;
        }
    }

    private int claimAndSubmit(
            String workerId,
            int remainingClaims,
            DrainRun run
    ) {
        if (!accepting.get() || remainingClaims <= 0) {
            return 0;
        }

        int requested = Math.min(
                Math.min(
                        properties.batchSize(),
                        properties.workerParallelism()
                ),
                remainingClaims
        );
        int acquired = acquireUpTo(requested);
        if (acquired == 0) {
            return 0;
        }

        List<RetentionClaim> claims;
        try {
            claims = claimRepository.claimExpired(
                    acquired,
                    properties.retryLimit(),
                    workerId,
                    properties.leaseDuration()
            );
        } catch (RuntimeException exception) {
            permits.release(acquired);
            throw exception;
        }

        if (metrics != null) {
            metrics.retentionClaimed(claims.size());
        }
        LOGGER.info(
                "retention_claim event=claimed count={} capacity={}",
                claims.size(),
                acquired
        );

        int unused = acquired - claims.size();
        if (unused > 0) {
            permits.release(unused);
        }

        int submitted = 0;
        for (RetentionClaim claim : claims) {
            try {
                workers.execute(() -> runClaim(claim, run));
                submitted++;
            } catch (RuntimeException exception) {
                boolean released = claimRepository.release(claim);
                LOGGER.warn(
                        "retention_claim event=submission_rejected generation={} released={}",
                        claim.generation(),
                        released
                );
                permits.release();
            }
        }
        return submitted;
    }

    private int acquireUpTo(int requested) {
        int acquired = 0;
        while (acquired < requested
                && accepting.get()
                && permits.tryAcquire()) {
            acquired++;
        }
        return acquired;
    }

    private void runClaim(
            RetentionClaim claim,
            DrainRun run
    ) {
        try {
            RetentionCleanupResult result = cleanupService.cleanup(claim);
            LOGGER.info(
                    "retention_cleanup event=completed status={} generation={} deletedChunks={}",
                    result.status(),
                    result.generation(),
                    result.deletedChunks()
            );
        } finally {
            permits.release();
            if (run != null) {
                synchronized (run) {
                    run.activeTasks--;
                }
                try {
                    refill(run);
                } catch (RuntimeException exception) {
                    LOGGER.error(
                            "retention_drain event=refill_failed errorType={}",
                            exception.getClass().getSimpleName()
                    );
                }
                completeDrainIfDone(run);
            }
        }
    }

    private void completeDrainIfDone(DrainRun run) {
        boolean done;
        synchronized (run) {
            done = run.activeTasks == 0
                    && (run.exhausted || run.remainingClaims <= 0);
        }
        if (!done) {
            return;
        }
        synchronized (drainMonitor) {
            if (activeDrain == run) {
                activeDrain = null;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        accepting.set(false);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    private static final class DrainRun {
        private final String workerId;
        private int remainingClaims;
        private int activeTasks;
        private boolean exhausted;

        private DrainRun(String workerId, int maxClaims) {
            this.workerId = workerId;
            this.remainingClaims = maxClaims;
        }
    }
}
