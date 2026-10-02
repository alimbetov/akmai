package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RetentionWorkerPoolTest {

    @Test
    void claimsOnlyImmediatelyAvailableWorkerCapacity() {
        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        ChunkRetentionService cleanup = mock(ChunkRetentionService.class);
        RetentionProperties properties = properties(2, 100);
        CountDownLatch block = new CountDownLatch(1);

        List<RetentionClaim> claimed = List.of(
                claim("doc-1", 1),
                claim("doc-2", 2)
        );
        when(claims.claimExpired(
                eq(2), eq(5), eq("pod-a"), eq(Duration.ofMinutes(10))
        )).thenReturn(claimed);
        when(cleanup.cleanup(any())).thenAnswer(invocation -> {
            block.await(5, TimeUnit.SECONDS);
            RetentionClaim claim = invocation.getArgument(0);
            return deleted(claim);
        });

        RetentionWorkerPool pool = new RetentionWorkerPool(
                claims,
                cleanup,
                properties
        );
        try {
            assertThat(pool.claimAndSubmit("pod-a")).isEqualTo(2);
            assertThat(pool.availableCapacity()).isZero();
            assertThat(pool.claimAndSubmit("pod-a")).isZero();
            verify(claims).claimExpired(
                    2, 5, "pod-a", Duration.ofMinutes(10)
            );
        } finally {
            block.countDown();
            pool.shutdown();
        }
    }

    private static RetentionProperties properties(
            int parallelism,
            int batch
    ) {
        return new RetentionProperties(
                true,
                "0 30 3 * * *",
                "UTC",
                batch,
                20,
                5,
                parallelism,
                8,
                Duration.ofMinutes(10),
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
    }

    private static RetentionClaim claim(String documentId, long generation) {
        return new RetentionClaim(
                documentId,
                generation,
                UUID.nameUUIDFromBytes(
                        (documentId + generation).getBytes()
                ),
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
