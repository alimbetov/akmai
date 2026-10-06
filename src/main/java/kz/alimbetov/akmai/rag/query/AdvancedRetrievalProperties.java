package kz.alimbetov.akmai.rag.query;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.retrieval.advanced")
public record AdvancedRetrievalProperties(
        @DefaultValue("false") boolean multiQueryEnabled,
        @DefaultValue("3") @Min(1) @Max(5) int multiQueryVariants,
        @DefaultValue("16") @Min(1) @Max(32) int maxExpandedChunks,
        @DefaultValue("false") boolean hydeEnabled,
        @DefaultValue("false") boolean queryMemoryEnabled,
        @DefaultValue("2048") @Min(32) @Max(10000) int queryMemoryMaxEntries,
        @DefaultValue("2") @Min(1) @Max(5) int queryMemoryMatches,
        @DefaultValue("0.86") @DecimalMin("0.0") @DecimalMax("1.0")
        double queryMemorySimilarityThreshold,
        @DefaultValue("1800") @Min(256) @Max(8000) int queryMemoryAnswerMaxChars,
        @DefaultValue("2s") @NotNull Duration modelTimeout,
        @DefaultValue("false") boolean colbertEnabled
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
