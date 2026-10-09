package kz.alimbetov.akmai.knowledge.ingestion.async;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncIngestionConfiguration {

    @Bean(name = "asyncIngestionExecutor", destroyMethod = "shutdown")
    public ExecutorService asyncIngestionExecutor(
            AsyncIngestionProperties properties,
            MeterRegistry meterRegistry
    ) {
        return BoundedExecutorFactory.createMonitored(
                properties.maxConcurrentIngestions(),
                properties.workerQueueCapacity(),
                meterRegistry,
                "async-ingestion"
        );
    }
}
