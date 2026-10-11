package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RetentionWorkerPoolFailureRecoveryTest {

    @Test
    void cleanupFailureDoesNotLeakPermitOrStopDrainRefill() throws Exception {
        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        ChunkRetentionService cleanup = mock(ChunkRetentionService.class);
        RetentionClaim first = claim("doc-failing", 1);
        RetentionClaim second = claim("doc-after-failure", 2);
        CountDownLatch secondCompleted = new CountDownLatch(1);

        when(claims.claimExpired(
                eq(1),
                eq(5),
                eq("pod-a"),
                eq(Duration.ofMinutes(10))
        )).thenReturn(List.of(first), List.of(second));
        when(cleanup.cleanup(first))
                .thenThrow(new IllegalStateException("cleanup failed"));
        when(cleanup.cleanup(second)).thenAnswer(invocation -> {
            secondCompleted.countDown();
            return deleted(second);
        });

        RetentionWorkerPool pool = new RetentionWorkerPool(
                claims,
                cleanup,
                properties()
        );
        try {
            assertThat(pool.drain("pod-a", 2)).isEqualTo(1);
            assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();

            verify(claims, times(2)).claimExpired(
                    1,
                    5,
                    "pod-a",
                    Duration.ofMinutes(10)
            );
            verify(cleanup).cleanup(first);
            verify(cleanup).cleanup(second);
        } finally {
            pool.shutdown();
        }
    }

    private static RetentionProperties properties() {
        return new RetentionProperties(
                true,
                "0 30 3 * * *",
                "UTC",
                100,
                20,
                5,
                1,
                1,
                Duration.ofMinutes(10),
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
    }

    private static RetentionClaim claim(String documentId, long generation) {
        return new RetentionClaim(
                documentId,
                generation,
                UUID.nameUUIDFromBytes((documentId + generation).getBytes()),
                "pod-a",
                Instant.now().plus(Duration.ofMinutes(10))
        );
    }

    private static RetentionCleanupResult deleted(RetentionClaim claim) {
        return new RetentionCleanupResult(
                claim.documentId(),
                claim.generation(),
                1,
                RetentionCleanupResult.Status.DELETED
        );
    }
}
