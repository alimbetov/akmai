package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import org.junit.jupiter.api.Test;

class GenerationReconciliationSchedulerControlFlowTest {

    @Test
    void disabledSchedulerPerformsNoWork() {
        GenerationReconciliationService service =
                mock(GenerationReconciliationService.class);
        GenerationReconciliationScheduler scheduler =
                new GenerationReconciliationScheduler(
                        service,
                        properties(false, 10, 3)
                );

        scheduler.reconcile();

        verify(service, never()).reconcileBatch();
    }

    @Test
    void shortBatchStopsRunImmediately() {
        GenerationReconciliationService service =
                mock(GenerationReconciliationService.class);
        when(service.reconcileBatch()).thenReturn(10, 10, 4, 10);
        GenerationReconciliationScheduler scheduler =
                new GenerationReconciliationScheduler(
                        service,
                        properties(true, 10, 5)
                );

        scheduler.reconcile();

        verify(service, times(3)).reconcileBatch();
    }

    @Test
    void fullBatchesCannotExceedRunBudget() {
        GenerationReconciliationService service =
                mock(GenerationReconciliationService.class);
        when(service.reconcileBatch()).thenReturn(10);
        GenerationReconciliationScheduler scheduler =
                new GenerationReconciliationScheduler(
                        service,
                        properties(true, 10, 3)
                );

        scheduler.reconcile();

        verify(service, times(3)).reconcileBatch();
    }

    private ReconciliationProperties properties(
            boolean enabled,
            int batchSize,
            int maxBatches
    ) {
        return new ReconciliationProperties(
                enabled,
                batchSize,
                maxBatches,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5)
        );
    }
}
