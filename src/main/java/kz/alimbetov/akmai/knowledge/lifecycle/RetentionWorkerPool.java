package kz.alimbetov.akmai.knowledge.lifecycle;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class RetentionWorkerPool {

    private final RetentionClaimRepository claimRepository;
    private final ChunkRetentionService cleanupService;
    private final RetentionProperties properties;
    private final ThreadPoolExecutor workers;
    private final Semaphore permits;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

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

    public int availableCapacity() {
        return accepting.get() ? permits.availablePermits() : 0;
    }

    public int claimAndSubmit(String workerId) {
        if (!accepting.get()) {
            return 0;
        }

        int requested = Math.min(
                properties.batchSize(),
                properties.workerParallelism()
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

        int unused = acquired - claims.size();
        if (unused > 0) {
            permits.release(unused);
        }

        int submitted = 0;
        for (RetentionClaim claim : claims) {
            try {
                workers.execute(() -> runClaim(claim));
                submitted++;
            } catch (RuntimeException exception) {
                claimRepository.release(claim);
                permits.release();
            }
        }
        return submitted;
    }

    private int acquireUpTo(int requested) {
        int acquired = 0;
        while (acquired < requested && accepting.get() && permits.tryAcquire()) {
            acquired++;
        }
        return acquired;
    }

    private void runClaim(RetentionClaim claim) {
        try {
            cleanupService.cleanup(claim);
        } finally {
            permits.release();
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
}
