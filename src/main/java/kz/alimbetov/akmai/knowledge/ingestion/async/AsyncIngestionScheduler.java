package kz.alimbetov.akmai.knowledge.ingestion.async;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.observability.AsyncIngestionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AsyncIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            AsyncIngestionScheduler.class
    );

    private final AsyncIngestionProperties properties;
    private final AsyncIngestionJobRepository repository;
    private final AsyncIngestionWorker worker;
    private final AsyncIngestionMetrics metrics;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final String leaseOwner = "async-worker:" + UUID.randomUUID();

    public AsyncIngestionScheduler(
            AsyncIngestionProperties properties,
            AsyncIngestionJobRepository repository,
            AsyncIngestionWorker worker,
            AsyncIngestionMetrics metrics,
            @Qualifier("asyncIngestionExecutor") ExecutorService executor
    ) {
        this.properties = properties;
        this.repository = repository;
        this.worker = worker;
        this.metrics = metrics;
        this.executor = executor;
        this.slots = new Semaphore(properties.maxConcurrentIngestions());
    }

    @Scheduled(
            fixedDelayString = "${akmai.ingestion.async-worker.poll-interval:1s}"
    )
    public void poll() {
        if (!properties.enabled()) {
            return;
        }

        try {
            metrics.queueDepth(repository.countBacklog());
        } catch (RuntimeException exception) {
            log.debug("Async ingestion backlog metric refresh failed", exception);
        }

        int requested = Math.min(
                properties.claimBatchSize(),
                slots.availablePermits()
        );
        if (requested <= 0) {
            return;
        }

        int reserved = reserve(requested);
        if (reserved == 0) {
            return;
        }

        List<AsyncIngestionClaim> claims;
        try {
            claims = repository.claimEligible(
                    leaseOwner,
                    reserved,
                    properties.leaseDuration()
            );
        } catch (RuntimeException exception) {
            slots.release(reserved);
            log.warn("Async ingestion claim failed", exception);
            return;
        }

        if (claims.size() < reserved) {
            slots.release(reserved - claims.size());
        }

        for (AsyncIngestionClaim claim : claims) {
            Instant acceptedAt = claim.job().acceptedAt();
            if (acceptedAt != null) {
                metrics.queueWait(Duration.between(acceptedAt, Instant.now()));
            }
            submit(claim);
        }
    }

    private int reserve(int requested) {
        int reserved = 0;
        while (reserved < requested && slots.tryAcquire()) {
            reserved++;
        }
        return reserved;
    }

    private void submit(AsyncIngestionClaim claim) {
        try {
            executor.execute(() -> {
                try {
                    worker.process(claim);
                } catch (RuntimeException exception) {
                    log.error(
                            "Unhandled async ingestion worker failure for {}",
                            claim.ingestionId(),
                            exception
                    );
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException exception) {
            slots.release();
            boolean rescheduled = repository.markRetry(
                    claim,
                    properties.pollInterval(),
                    false,
                    AsyncIngestionFailureClassifier.Classification.RETRYABLE.name(),
                    "ASYNC_EXECUTOR_REJECTED",
                    exception.getMessage()
            );
            if (rescheduled) {
                metrics.retry();
            } else {
                metrics.leaseLost();
            }
        }
    }
}
