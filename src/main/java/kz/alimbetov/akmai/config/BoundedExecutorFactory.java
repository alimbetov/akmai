package kz.alimbetov.akmai.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BoundedExecutorFactory {

    private BoundedExecutorFactory() {
    }

    public static ExecutorService create(int parallelism, int queueCapacity) {
        int workers = Math.max(1, parallelism);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
