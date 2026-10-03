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
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void drainRefillsWorkersUntilRunLimitIsSubmitted() throws Exception {
        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        ChunkRetentionService cleanup = mock(ChunkRetentionService.class);
        RetentionProperties properties = properties(2, 100);
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(5);

        when(claims.claimExpired(
                org.mockito.ArgumentMatchers.anyInt(),
                eq(5),
                eq("pod-a"),
                eq(Duration.ofMinutes(10))
        )).thenAnswer(invocation -> {
            int requested = invocation.getArgument(0);
            java.util.ArrayList<RetentionClaim> result =
                    new java.util.ArrayList<>();
            for (int index = 0; index < requested; index++) {
                int next = sequence.incrementAndGet();
                if (next > 5) {
                    break;
                }
                result.add(claim("doc-" + next, next));
            }
            return List.copyOf(result);
        });
        when(cleanup.cleanup(any())).thenAnswer(invocation -> {
            completed.countDown();
            return deleted(invocation.getArgument(0));
        });

        RetentionWorkerPool pool = new RetentionWorkerPool(
                claims,
                cleanup,
                properties
        );
        try {
            assertThat(pool.drain("pod-a", 5)).isEqualTo(2);
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            verify(cleanup, org.mockito.Mockito.times(5)).cleanup(any());
            assertThat(sequence.get()).isGreaterThanOrEqualTo(5);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void shutdownDrainsRunningCleanupAndRejectsNewClaims() throws Exception {
        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        ChunkRetentionService cleanup = mock(ChunkRetentionService.class);
        RetentionProperties properties = properties(1, 1);
        RetentionClaim claim = claim("doc-shutdown", 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(claims.claimExpired(
                eq(1), eq(5), eq("pod-a"), eq(Duration.ofMinutes(10))
        )).thenReturn(List.of(claim));
        when(cleanup.cleanup(claim)).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return deleted(claim);
        });

        RetentionWorkerPool pool = new RetentionWorkerPool(
                claims,
                cleanup,
                properties
        );
        assertThat(pool.claimAndSubmit("pod-a")).isEqualTo(1);
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        Thread shutdown = new Thread(pool::shutdown);
        shutdown.start();
        Thread.sleep(50);
        assertThat(shutdown.isAlive()).isTrue();

        release.countDown();
        shutdown.join(2_000);

        assertThat(shutdown.isAlive()).isFalse();
        assertThat(pool.availableCapacity()).isZero();
        assertThat(pool.claimAndSubmit("pod-a")).isZero();
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
    @Test
    void retentionWorkerHasNoSharedHeartbeatSchedulerOrRenewalQueue() {
        assertThat(java.util.Arrays.stream(
                RetentionWorkerPool.class.getDeclaredFields()
        ).map(field -> field.getType().getName()))
                .noneMatch(type -> type.contains("ScheduledExecutor")
                        || type.contains("ScheduledThreadPoolExecutor"));

        assertThat(java.util.Arrays.stream(
                RetentionWorkerPool.class.getDeclaredMethods()
        ).map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                        .contains("heartbeat")
                        || name.toLowerCase(java.util.Locale.ROOT)
                        .contains("renewlease"));
    }

}
