package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetentionSchedulerControlFlowTest {

    @Test
    void disabledSchedulerPerformsNoRecoveryOrDrainWork() {
        RetentionWorkerPool workers = mock(RetentionWorkerPool.class);
        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        DocumentGenerationRepository generations =
                mock(DocumentGenerationRepository.class);
        RetentionScheduler scheduler = new RetentionScheduler(
                workers,
                claims,
                properties(false, 10, 3),
                generations
        );

        scheduler.cleanupExpiredDocuments();

        verify(generations, never()).failStaleIngestionBatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(workers, never()).drain(
                anyString(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(workers, never()).backlogCount();
        verify(claims, never()).countRetryExhausted(
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void staleRecoveryStopsOnFirstPartialBatchAndUsesFullDrainBudget() {
        RetentionWorkerPool workers = mock(RetentionWorkerPool.class);
        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        DocumentGenerationRepository generations =
                mock(DocumentGenerationRepository.class);
        RetentionProperties properties = properties(true, 10, 4);

        when(generations.failStaleIngestionBatch(
                properties.leaseDuration(),
                10
        )).thenReturn(10, 10, 4, 10);
        when(workers.backlogCount()).thenReturn(0L);
        when(claims.countRetryExhausted(properties.retryLimit()))
                .thenReturn(0L);

        RetentionScheduler scheduler = new RetentionScheduler(
                workers,
                claims,
                properties,
                generations
        );

        scheduler.cleanupExpiredDocuments();

        verify(generations, times(3)).failStaleIngestionBatch(
                properties.leaseDuration(),
                10
        );
        verify(workers).drain(anyString(), eq(40));
    }

    @Test
    void fullRecoveryBatchesCannotExceedConfiguredMaximum() {
        RetentionWorkerPool workers = mock(RetentionWorkerPool.class);
        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        DocumentGenerationRepository generations =
                mock(DocumentGenerationRepository.class);
        RetentionProperties properties = properties(true, 10, 3);

        when(generations.failStaleIngestionBatch(
                properties.leaseDuration(),
                10
        )).thenReturn(10);
        when(workers.backlogCount()).thenReturn(0L);
        when(claims.countRetryExhausted(properties.retryLimit()))
                .thenReturn(0L);

        RetentionScheduler scheduler = new RetentionScheduler(
                workers,
                claims,
                properties,
                generations
        );

        scheduler.cleanupExpiredDocuments();

        verify(generations, times(3)).failStaleIngestionBatch(
                properties.leaseDuration(),
                10
        );
        verify(workers).drain(anyString(), eq(30));
    }

    private RetentionProperties properties(
            boolean enabled,
            int batchSize,
            int maxBatches
    ) {
        return new RetentionProperties(
                enabled,
                "0 0 * * * *",
                "UTC",
                batchSize,
                maxBatches,
                3,
                2,
                4,
                Duration.ofMinutes(10),
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
    }
}
