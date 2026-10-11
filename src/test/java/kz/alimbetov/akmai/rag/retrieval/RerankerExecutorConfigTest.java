package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RerankerExecutorConfigTest {

    @Test
    void productionExecutorIsSingleThreadedBoundedAndRejectsOnSaturation()
            throws Exception {
        RetrievalProperties properties = RetrievalTestProperties.defaults();
        ThreadPoolExecutor executor = (ThreadPoolExecutor)
                new RerankerExecutorConfig().rerankerExecutor(properties);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(1);
            assertThat(executor.getQueue().remainingCapacity())
                    .isEqualTo(properties.rerankerCandidates());

            executor.execute(() -> {
                running.countDown();
                await(release);
            });
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();

            for (int index = 0; index < properties.rerankerCandidates(); index++) {
                executor.execute(() -> await(release));
            }

            assertThatThrownBy(() -> executor.execute(() -> {
            })).isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
