package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.vector")
public record VectorStorageProperties(
        @NotNull @Min(1) Integer dimensions,
        @NotBlank String indexType,
        @NotBlank String distanceType,
        @NotNull @Min(1) @Max(10000) Integer maxDocumentBatchSize,
        @NotNull Duration embeddingHttpTimeout,
        @NotNull Duration dbTransactionTimeout,
        @NotBlank String tokenizerProfile
) {
    public VectorStorageProperties(
            Integer dimensions,
            String indexType,
            String distanceType,
            Integer maxDocumentBatchSize
    ) {
        this(
                dimensions,
                indexType,
                distanceType,
                maxDocumentBatchSize,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                "conservative-v1"
        );
    }

    public VectorStorageProperties {
        if (embeddingHttpTimeout == null
                || embeddingHttpTimeout.isZero()
                || embeddingHttpTimeout.isNegative()) {
            throw new IllegalArgumentException("embedding-http-timeout must be positive");
        }
        if (dbTransactionTimeout == null
                || dbTransactionTimeout.isZero()
                || dbTransactionTimeout.isNegative()) {
            throw new IllegalArgumentException("db-transaction-timeout must be positive");
        }
    }
}
