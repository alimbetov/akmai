package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class AdaptiveGraphDreamSchedulerTest {

    @Test
    void triggerDelegatesExactlyOnceToCoordinator() {
        AdaptiveGraphDreamCoordinator coordinator =
                mock(AdaptiveGraphDreamCoordinator.class);
        AdaptiveGraphDreamScheduler scheduler =
                new AdaptiveGraphDreamScheduler(coordinator);

        scheduler.runScheduled();

        verify(coordinator).runOnce();
    }

    @Test
    void coordinatorFailureIsNotSilentlyConvertedIntoSuccess() {
        AdaptiveGraphDreamCoordinator coordinator =
                mock(AdaptiveGraphDreamCoordinator.class);
        IllegalStateException failure = new IllegalStateException("lease unavailable");
        doThrow(failure).when(coordinator).runOnce();
        AdaptiveGraphDreamScheduler scheduler =
                new AdaptiveGraphDreamScheduler(coordinator);

        assertThatThrownBy(scheduler::runScheduled).isSameAs(failure);
    }
}
