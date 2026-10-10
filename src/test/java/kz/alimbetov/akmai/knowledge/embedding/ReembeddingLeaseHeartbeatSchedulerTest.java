package kz.alimbetov.akmai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class ReembeddingLeaseHeartbeatSchedulerTest {

    @Test
    void heartbeatDelegatesExactlyOnceToLeaseManager() {
        ReembeddingLeaseManager leases = mock(ReembeddingLeaseManager.class);
        ReembeddingLeaseHeartbeatScheduler scheduler =
                new ReembeddingLeaseHeartbeatScheduler(leases);

        scheduler.heartbeat();

        verify(leases).renewOwnedActiveLeases();
    }

    @Test
    void leaseManagerFailureIsNotSilentlyConvertedIntoSuccess() {
        ReembeddingLeaseManager leases = mock(ReembeddingLeaseManager.class);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(leases).renewOwnedActiveLeases();
        ReembeddingLeaseHeartbeatScheduler scheduler =
                new ReembeddingLeaseHeartbeatScheduler(leases);

        assertThatThrownBy(scheduler::heartbeat).isSameAs(failure);
    }
}
