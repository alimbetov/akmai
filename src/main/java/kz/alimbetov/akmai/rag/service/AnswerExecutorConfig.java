package kz.alimbetov.akmai.rag.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerExecutorConfig {

    @Bean(name = "answerExecutor", destroyMethod = "shutdown")
    public ExecutorService answerExecutor(
            MeterRegistry meterRegistry
    ) {
        return BoundedExecutorFactory.createMonitored(
                2,
                16,
                meterRegistry,
                "answer"
        );
    }
}
