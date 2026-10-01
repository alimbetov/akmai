package kz.alimbetov.akmai.rag.retrieval;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RerankerExecutorConfig {

    @Bean(name = "rerankerExecutor", destroyMethod = "shutdown")
    public ExecutorService rerankerExecutor(RetrievalProperties properties) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.rerankerCandidates()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
