package kz.alimbetov.akmai.knowledge.ingestion.async;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
        prefix = "akmai.ingestion.async-worker",
        name = "enabled",
        havingValue = "true"
)
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
