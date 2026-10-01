package kz.alimbetov.akmai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.ai.vectorstore.pgvector")
public record VectorStorageProperties(
        Integer dimensions,
        String indexType,
        String distanceType,
        Integer maxDocumentBatchSize
) {
}
