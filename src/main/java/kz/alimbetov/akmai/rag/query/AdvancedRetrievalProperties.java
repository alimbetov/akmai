package kz.alimbetov.akmai.rag.query;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.retrieval.advanced")
public record AdvancedRetrievalProperties(
        boolean multiQueryEnabled,
        @Min(1) @Max(5) int multiQueryVariants,
        @Min(1) @Max(32) int maxExpandedChunks,
        boolean hydeEnabled,
        boolean queryMemoryEnabled,
        @Min(32) @Max(10000) int queryMemoryMaxEntries,
        @Min(1) @Max(5) int queryMemoryMatches,
        @DecimalMin("0.0") @DecimalMax("1.0") double queryMemorySimilarityThreshold,
        @Min(256) @Max(8000) int queryMemoryAnswerMaxChars,
        @NotNull Duration modelTimeout,
        boolean colbertEnabled
) {
    public AdvancedRetrievalProperties {
        if (modelTimeout == null
                || modelTimeout.compareTo(Duration.ofMillis(50)) < 0
                || modelTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "model-timeout must be between PT0.05S and PT30S"
            );
        }
    }
}
