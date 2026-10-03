package kz.alimbetov.akmai.rag.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrievalExecutorConfig {

    @Bean(name = "retrievalExecutor", destroyMethod = "shutdown")
    public ExecutorService retrievalExecutor(
            @Value("${akmai.retrieval.parallelism:8}") int parallelism,
            @Value("${akmai.retrieval.queue-capacity:64}") int queueCapacity,
            MeterRegistry meterRegistry
    ) {
        return BoundedExecutorFactory.createMonitored(
                parallelism,
                queueCapacity,
                meterRegistry,
                "retrieval"
        );
    }
}
