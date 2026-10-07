package kz.alimbetov.akmai.rag.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShadowPolicyExecutorConfiguration {

    @Bean(name = "shadowPolicyExecutor", destroyMethod = "shutdown")
    public ExecutorService shadowPolicyExecutor(MeterRegistry meterRegistry) {
        return BoundedExecutorFactory.createMonitored(
                1,
                8,
                meterRegistry,
                "rag-policy-shadow"
        );
    }
}
