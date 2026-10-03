package kz.alimbetov.akmai.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BoundedExecutorFactory {

    private BoundedExecutorFactory() {
    }

    public static ExecutorService create(
            int parallelism,
            int queueCapacity
    ) {
        return createExecutor(
                parallelism,
                queueCapacity,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public static ExecutorService createMonitored(
            int parallelism,
            int queueCapacity,
            MeterRegistry registry,
            String role
    ) {
        if (registry == null) {
            throw new IllegalArgumentException(
                    "registry must not be null"
            );
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException(
                    "role must not be blank"
            );
        }

        var rejected = registry.counter(
                "akmai.executor.rejected",
                "role",
                role
        );
        ThreadPoolExecutor executor = createExecutor(
                parallelism,
                queueCapacity,
                (task, pool) -> {
                    rejected.increment();
                    throw new RejectedExecutionException(
                            "Executor saturated: " + role
                    );
                }
        );

        return ExecutorServiceMetrics.monitor(
                registry,
                executor,
                "akmai.executor",
                Tags.of("role", role)
        );
    }

    private static ThreadPoolExecutor createExecutor(
            int parallelism,
            int queueCapacity,
            java.util.concurrent.RejectedExecutionHandler rejectedHandler
    ) {
        int workers = Math.max(1, parallelism);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                rejectedHandler
        );
    }
}
