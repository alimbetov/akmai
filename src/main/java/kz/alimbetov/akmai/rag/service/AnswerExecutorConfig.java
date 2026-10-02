package kz.alimbetov.akmai.rag.service;

import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerExecutorConfig {

    @Bean(name = "answerExecutor", destroyMethod = "shutdown")
    public ExecutorService answerExecutor() {
        return BoundedExecutorFactory.create(2, 16);
    }
}
