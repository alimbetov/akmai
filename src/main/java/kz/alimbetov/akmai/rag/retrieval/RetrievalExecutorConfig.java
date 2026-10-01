package kz.alimbetov.akmai.rag.retrieval;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class RetrievalExecutorConfig {
    @Bean(name = "retrievalExecutor", destroyMethod = "shutdown")
    public Executor retrievalExecutor(@Value("${akmai.retrieval.parallelism:8}") int parallelism) {
        return Executors.newFixedThreadPool(Math.max(1, parallelism));
    }
}
