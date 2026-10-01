package kz.alimbetov.akmai.knowledge.ingestion;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionExecutorConfig {

    @Bean(name = "ingestionExecutor", destroyMethod = "shutdown")
    public Executor ingestionExecutor(
            @Value("${akmai.ingestion.parallelism:8}") int parallelism
    ) {
        return Executors.newFixedThreadPool(Math.max(1, parallelism));
    }
}
