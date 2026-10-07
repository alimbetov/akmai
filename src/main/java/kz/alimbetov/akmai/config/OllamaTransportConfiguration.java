package kz.alimbetov.akmai.config;

import java.time.Duration;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OllamaTransportConfiguration {

    @Bean(name = "vectorWriteOllamaApi")
    public OllamaApi vectorWriteOllamaApi(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
            String baseUrl,
            VectorStorageProperties properties
    ) {
        return api(baseUrl, properties.embeddingHttpTimeout());
    }

    @Bean(name = "retrievalOllamaApi")
    public OllamaApi retrievalOllamaApi(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
            String baseUrl,
            RetrievalProperties properties
    ) {
        return api(
                baseUrl,
                minimum(
                        properties.embeddingHttpTimeout(),
                        properties.strategyTimeout()
                )
        );
    }

    @Bean(name = "chatOllamaApi")
    public OllamaApi chatOllamaApi(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
            String baseUrl,
            RetrievalProperties properties
    ) {
        return api(baseUrl, properties.answerTimeout());
    }

    @Bean(name = "vectorWriteEmbeddingModel")
    public OllamaEmbeddingModel vectorWriteEmbeddingModel(
            @Qualifier("vectorWriteOllamaApi") OllamaApi api,
            VectorStorageProperties vectorProperties,
            @Value("${spring.ai.ollama.embedding.model:qwen3-embedding:4b}")
            String model
    ) {
        return embeddingModel(api, model, vectorProperties.dimensions());
    }

    @Bean(name = "retrievalEmbeddingModel")
    public OllamaEmbeddingModel retrievalEmbeddingModel(
            @Qualifier("retrievalOllamaApi") OllamaApi api,
            VectorStorageProperties vectorProperties,
            @Value("${spring.ai.ollama.embedding.model:qwen3-embedding:4b}")
            String model
    ) {
        return embeddingModel(api, model, vectorProperties.dimensions());
    }

    @Bean(name = "chatModel")
    @Primary
    public OllamaChatModel chatModel(
            @Qualifier("chatOllamaApi") OllamaApi api,
            @Value("${spring.ai.ollama.chat.options.model:qwen3:8b}")
            String model,
            @Value("${spring.ai.ollama.chat.options.temperature:0.1}")
            Double temperature
    ) {
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(
                        OllamaChatOptions.builder()
                                .model(model)
                                .temperature(temperature)
                                .build()
                )
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
    }

    private OllamaEmbeddingModel embeddingModel(
            OllamaApi api,
            String model,
            int dimensions
    ) {
        CanonicalEmbeddingContract.validate(model, dimensions);
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(
                        OllamaEmbeddingOptions.builder()
                                .model(model)
                                .dimensions(dimensions)
                                .build()
                )
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
    }

    private OllamaApi api(String baseUrl, Duration timeout) {
        int timeoutMillis = toMillis(timeout);
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(
                        RestClient.builder()
                                .requestFactory(requestFactory)
                )
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(new DefaultResponseErrorHandler())
                .build();
    }

    private Duration minimum(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private int toMillis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Ollama transport timeout must be positive"
            );
        }
        long millis = timeout.toMillis();
        return Math.toIntExact(Math.min(millis, Integer.MAX_VALUE));
    }
}
