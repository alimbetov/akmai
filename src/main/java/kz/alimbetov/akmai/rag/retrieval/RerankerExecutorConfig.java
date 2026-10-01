package kz.alimbetov.akmai.rag.retrieval;

import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RerankerExecutorConfig {

    @Bean(name = "rerankerExecutor", destroyMethod = "shutdown")
    public ExecutorService rerankerExecutor(RetrievalProperties properties) {
        return BoundedExecutorFactory.create(1, properties.rerankerCandidates());
    }
}
