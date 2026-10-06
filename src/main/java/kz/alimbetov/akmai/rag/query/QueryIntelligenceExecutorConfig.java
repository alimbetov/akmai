package kz.alimbetov.akmai.rag.query;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryIntelligenceExecutorConfig {

    @Bean(name = "queryIntelligenceExecutor", destroyMethod = "shutdown")
    public ExecutorService queryIntelligenceExecutor(
            MeterRegistry meterRegistry
    ) {
        return BoundedExecutorFactory.createMonitored(
                2,
                16,
                meterRegistry,
                "query-intelligence"
        );
    }
}
