package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import org.junit.jupiter.api.Test;

class AsyncIngestionHeartbeatTest {

    @Test
    void successfulRenewKeepsOwnership() throws Exception {
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionProperties properties = properties();
        AsyncIngestionClaim claim = claim();
        when(repository.renew(claim, properties.leaseDuration())).thenReturn(true);
        AsyncIngestionHeartbeat heartbeat = new AsyncIngestionHeartbeat(
                repository,
                properties
        );
        AsyncIngestionHeartbeat.Handle handle = heartbeat.register(claim);

        invokeHeartbeat(heartbeat);

        assertThat(handle.ownershipLost()).isFalse();
        verify(repository).renew(claim, properties.leaseDuration());
        handle.close();
    }

    @Test
    void failedRenewPermanentlyMarksOwnershipLost() throws Exception {
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionProperties properties = properties();
        AsyncIngestionClaim claim = claim();
        when(repository.renew(claim, properties.leaseDuration())).thenReturn(false);
        AsyncIngestionHeartbeat heartbeat = new AsyncIngestionHeartbeat(
                repository,
                properties
        );
        AsyncIngestionHeartbeat.Handle handle = heartbeat.register(claim);

        invokeHeartbeat(heartbeat);
        invokeHeartbeat(heartbeat);

        assertThat(handle.ownershipLost()).isTrue();
        verify(repository, times(1)).renew(claim, properties.leaseDuration());
        handle.close();
    }

    @Test
    void renewalExceptionMarksOwnershipLostWithoutEscapingCycle() throws Exception {
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionProperties properties = properties();
        AsyncIngestionClaim claim = claim();
        when(repository.renew(claim, properties.leaseDuration()))
                .thenThrow(new IllegalStateException("database unavailable"));
        AsyncIngestionHeartbeat heartbeat = new AsyncIngestionHeartbeat(
                repository,
                properties
        );
        AsyncIngestionHeartbeat.Handle handle = heartbeat.register(claim);

        invokeHeartbeat(heartbeat);

        assertThat(handle.ownershipLost()).isTrue();
        handle.close();
    }

    @Test
    void closedHandleIsRemovedAndNoLongerRenewed() throws Exception {
        AsyncIngestionJobRepository repository = mock(AsyncIngestionJobRepository.class);
        AsyncIngestionProperties properties = properties();
        AsyncIngestionClaim claim = claim();
        AsyncIngestionHeartbeat heartbeat = new AsyncIngestionHeartbeat(
                repository,
                properties
        );
        AsyncIngestionHeartbeat.Handle handle = heartbeat.register(claim);

        handle.close();
        invokeHeartbeat(heartbeat);

        verify(repository, never()).renew(claim, properties.leaseDuration());
    }

    @Test
    void duplicateRegistrationForSameIngestionIsRejected() {
        AsyncIngestionHeartbeat heartbeat = new AsyncIngestionHeartbeat(
                mock(AsyncIngestionJobRepository.class),
                properties()
        );
        AsyncIngestionClaim claim = claim();
        AsyncIngestionHeartbeat.Handle first = heartbeat.register(claim);

        try {
            assertThatThrownBy(() -> heartbeat.register(claim))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(claim.ingestionId().toString());
        } finally {
            first.close();
        }
    }

    private void invokeHeartbeat(AsyncIngestionHeartbeat heartbeat) throws Exception {
        Method method = AsyncIngestionHeartbeat.class.getDeclaredMethod("heartbeatSafely");
        method.setAccessible(true);
        method.invoke(heartbeat);
    }

    private AsyncIngestionProperties properties() {
        return new AsyncIngestionProperties(
                true,
                3,
                3,
                3,
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
