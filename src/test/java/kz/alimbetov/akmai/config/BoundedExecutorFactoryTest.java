package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
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
}
