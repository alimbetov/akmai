package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RetentionWorkerPoolTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    void claimsOnlyAvailableExecutorCapacity() {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        ChunkRetentionService cleanup = mock(ChunkRetentionService.class);
        RetentionProperties properties = properties(2, 3, 100);
        CountDownLatch block = new CountDownLatch(1);
        when(lifecycle.claimExpired(eq(NOW), eq(5), eq(5), eq("pod-a"), any()))
                .thenReturn(claims(5));
        when(cleanup.cleanup(any())).thenAnswer(invocation -> {
            block.await(5, TimeUnit.SECONDS);
            RetentionClaim claim = invocation.getArgument(0);
            return deleted(claim);
        });

        RetentionWorkerPool pool = new RetentionWorkerPool(
                lifecycle, cleanup, properties, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        try {
            assertThat(pool.claimAndSubmit("pod-a")).isEqualTo(5);
            assertThat(pool.availableCapacity()).isZero();
            assertThat(pool.claimAndSubmit("pod-a")).isZero();
            verify(lifecycle).claimExpired(NOW, 5, 5, "pod-a", Duration.ofMinutes(10));
        } finally {
            block.countDown();
            pool.shutdown();
        }
    }

    @Test
    void heartbeatPeriodIsOneThirdOfLease() {
        assertThat(RetentionWorkerPool.heartbeatPeriod(Duration.ofMinutes(9)))
                .isEqualTo(Duration.ofMinutes(3));
    }

    private static RetentionProperties properties(int parallelism, int queue, int batch) {
        return new RetentionProperties(
                true,
                "0 30 3 * * *",
                "UTC",
                batch,
                20,
                5,
                parallelism,
                queue,
                Duration.ofMinutes(10),
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
    }

    private static List<RetentionClaim> claims(int count) {
        List<RetentionClaim> claims = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            claims.add(new RetentionClaim(
                    "doc-" + i,
                    1,
                    UUID.nameUUIDFromBytes(("claim-" + i).getBytes()),
                    "pod-a",
                    NOW.plus(Duration.ofMinutes(10))
            ));
        }
        return claims;
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
