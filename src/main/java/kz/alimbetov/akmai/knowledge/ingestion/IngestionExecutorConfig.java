package kz.alimbetov.akmai.knowledge.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionExecutorConfig {

    @Bean(name = "ingestionExecutor", destroyMethod = "shutdown")
    public ExecutorService ingestionExecutor(
            @Value("${akmai.ingestion.parallelism:8}") int parallelism,
            @Value("${akmai.ingestion.queue-capacity:128}") int queueCapacity,
            MeterRegistry meterRegistry
    ) {
        return BoundedExecutorFactory.createMonitored(
                parallelism,
                queueCapacity,
                meterRegistry,
                "ingestion"
        );
    }
}
