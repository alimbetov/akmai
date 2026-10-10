package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.observability.AsyncIngestionMetrics;
import org.junit.jupiter.api.Test;

class AsyncIngestionSchedulerTest {

    @Test
    void pollNeverClaimsBeyondAvailableDocumentSlots() {
        AsyncIngestionProperties properties = properties(2, 8);
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionWorker worker = mock(AsyncIngestionWorker.class);
        AsyncIngestionMetrics metrics = mock(AsyncIngestionMetrics.class);
        ExecutorService executor = mock(ExecutorService.class);
        List<Runnable> submitted = new ArrayList<>();
        doAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));

        AsyncIngestionClaim first = claim();
        AsyncIngestionClaim second = claim();
        when(repository.claimEligible(
                anyString(),
                eq(2),
                eq(properties.leaseDuration())
        )).thenReturn(List.of(first, second), List.of());

        AsyncIngestionScheduler scheduler = new AsyncIngestionScheduler(
                properties,
                repository,
                worker,
                metrics,
                executor
        );

        scheduler.poll();
        scheduler.poll();

        verify(repository, times(1)).claimEligible(
                anyString(),
                eq(2),
                eq(properties.leaseDuration())
        );
        verify(executor, times(2)).execute(any(Runnable.class));

        submitted.forEach(Runnable::run);
        scheduler.poll();

        verify(repository, times(2)).claimEligible(
                anyString(),
                eq(2),
                eq(properties.leaseDuration())
        );
        verify(worker).process(first);
        verify(worker).process(second);
    }

    @Test
    void claimFailureReleasesReservedSlotsForNextPoll() {
        AsyncIngestionProperties properties = properties(2, 8);
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionWorker worker = mock(AsyncIngestionWorker.class);
        AsyncIngestionMetrics metrics = mock(AsyncIngestionMetrics.class);
        ExecutorService executor = mock(ExecutorService.class);

        when(repository.claimEligible(
                anyString(),
                eq(2),
                eq(properties.leaseDuration())
        )).thenThrow(new IllegalStateException("db unavailable"))
                .thenReturn(List.of());

        AsyncIngestionScheduler scheduler = new AsyncIngestionScheduler(
                properties,
                repository,
                worker,
                metrics,
                executor
        );

        scheduler.poll();
        scheduler.poll();

        verify(repository, times(2)).claimEligible(
                anyString(),
                eq(2),
                eq(properties.leaseDuration())
        );
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void executorRejectionReschedulesDurableJobAndReleasesCapacity() {
        AsyncIngestionProperties properties = properties(1, 4);
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionWorker worker = mock(AsyncIngestionWorker.class);
        AsyncIngestionMetrics metrics = mock(AsyncIngestionMetrics.class);
        ExecutorService executor = mock(ExecutorService.class);
        AsyncIngestionClaim rejected = claim();

        when(repository.claimEligible(
                anyString(),
                eq(1),
                eq(properties.leaseDuration())
        )).thenReturn(List.of(rejected), List.of());
        when(repository.markRetry(
                eq(rejected),
                eq(properties.pollInterval()),
                eq(false),
                eq(AsyncIngestionFailureClassifier.Classification.RETRYABLE.name()),
                eq("ASYNC_EXECUTOR_REJECTED"),
                anyString()
        )).thenReturn(true);
        doThrow(new RejectedExecutionException("executor full"))
                .when(executor)
                .execute(any(Runnable.class));

        AsyncIngestionScheduler scheduler = new AsyncIngestionScheduler(
                properties,
                repository,
                worker,
                metrics,
                executor
        );

        scheduler.poll();
        scheduler.poll();

        verify(repository).markRetry(
                eq(rejected),
                eq(properties.pollInterval()),
                eq(false),
                eq(AsyncIngestionFailureClassifier.Classification.RETRYABLE.name()),
                eq("ASYNC_EXECUTOR_REJECTED"),
                eq("executor full")
        );
        verify(metrics).retry();
        verify(repository, times(2)).claimEligible(
                anyString(),
                eq(1),
                eq(properties.leaseDuration())
        );
        verify(worker, never()).process(any());
    }

    @Test
    void backlogMetricFailureDoesNotBlockDurableClaiming() {
        AsyncIngestionProperties properties = properties(1, 1);
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionWorker worker = mock(AsyncIngestionWorker.class);
        AsyncIngestionMetrics metrics = mock(AsyncIngestionMetrics.class);
        ExecutorService executor = mock(ExecutorService.class);

        when(repository.countBacklog()).thenThrow(new IllegalStateException("probe failed"));
        when(repository.claimEligible(
                anyString(),
                eq(1),
                eq(properties.leaseDuration())
        )).thenReturn(List.of());

        AsyncIngestionScheduler scheduler = new AsyncIngestionScheduler(
                properties,
                repository,
                worker,
                metrics,
                executor
        );

        scheduler.poll();

        verify(repository).claimEligible(
                anyString(),
                eq(1),
                eq(properties.leaseDuration())
        );
    }

    private AsyncIngestionProperties properties(
            int maxConcurrent,
            int claimBatchSize
    ) {
        return new AsyncIngestionProperties(
                true,
                maxConcurrent,
                maxConcurrent,
                claimBatchSize,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                5,
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                1024 * 1024
        );
    }

    private AsyncIngestionClaim claim() {
        UUID id = UUID.randomUUID();
        AsyncIngestionJob job = new AsyncIngestionJob(
                id,
                1,
                "event-" + id,
                "request-" + id,
                "job-fingerprint-" + id,
                "async-ingestion:" + id,
                "document-" + id,
                1L,
                "FILE",
                "file-" + id,
                "1",
                "sha256:content",
                "sha256:canonical",
                "INLINE",
                "{}",
                null,
                AsyncIngestionJobStatus.PROCESSING,
                1,
                0,
                null,
                "owner",
                null,
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        return new AsyncIngestionClaim(id, "owner", 1L, job);
    }
}
