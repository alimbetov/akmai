package kz.alimbetov.akmai.rag.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShadowEvaluationExecutorConfig {

    @Bean(name = "shadowEvaluationExecutor", destroyMethod = "shutdown")
    public ExecutorService shadowEvaluationExecutor(MeterRegistry meterRegistry) {
        return BoundedExecutorFactory.createMonitored(
                1,
                8,
                meterRegistry,
                "rag-shadow"
        );
    }
}
