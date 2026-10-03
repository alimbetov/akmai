package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BoundedExecutorFactoryTest {

    @Test
    void createsExecutorWithBoundedQueue() {
        var executor = BoundedExecutorFactory.create(2, 7);
        try {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            var pool = (ThreadPoolExecutor) executor;
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(7);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void saturationRejectsInsteadOfRunningWorkOnCallerThread() throws Exception {
        ThreadPoolExecutor executor =
                (ThreadPoolExecutor) BoundedExecutorFactory.create(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean callerRan = new AtomicBoolean(false);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> await(release));

            assertThatThrownBy(() ->
                    executor.execute(() -> callerRan.set(true))
            ).isInstanceOf(RejectedExecutionException.class);
            assertThat(callerRan).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void monitoredExecutorCountsRejectedWork() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var executor = BoundedExecutorFactory.createMonitored(
                1,
                1,
                registry,
                "test"
        );
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> await(release));

            assertThatThrownBy(() ->
                    executor.execute(() -> {
                    })
            ).isInstanceOf(RejectedExecutionException.class);

            assertThat(registry.get("akmai.executor.rejected")
                    .tag("role", "test")
                    .counter()
                    .count()).isEqualTo(1.0);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void asyncSubmissionAfterShutdownFailsPromptly() {
        var executor = BoundedExecutorFactory.create(1, 1);
        executor.shutdownNow();

        assertThatThrownBy(() ->
                CompletableFuture.supplyAsync(() -> "never", executor)
        ).isInstanceOf(RejectedExecutionException.class);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
