package kz.alimbetov.akmai.rag.retrieval;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.retrieval")
public record RetrievalProperties(
        @Min(1) @Max(64) int parallelism,
        @Min(1) @Max(1024) int queueCapacity,
        @Min(1) @Max(100) int vectorTopK,
        @DecimalMin("0.0") @DecimalMax("1.0") double vectorSimilarityThreshold,
        @Min(1) @Max(100) int lexicalLimit,
        @Min(1) @Max(100) int identifierLimit,
        @Min(1) @Max(100) int referenceLimit,
        @Min(1) @Max(1000) int rrfK,
        @Min(1) @Max(100) int expansionSeeds,
        @Min(0) @Max(10) int expansionRadius,
        @Min(0) @Max(100) int expansionMax,
        @Min(256) @Max(32768) int contextMaxTokens,
        @Min(1) @Max(100) int contextMaxChunks,
        @Min(1) @Max(100) int contextMaxChunksPerDocument,
        boolean rerankerEnabled,
        @Min(1) @Max(100) int rerankerCandidates,
        @NotNull Duration rerankerTimeout,
        @DecimalMin("0.0") @DecimalMax("1.0") double rerankerFusedWeight,
        @NotNull Duration requestTimeout,
        @NotNull Duration strategyTimeout,
        @NotNull Duration answerTimeout,
        @NotNull Duration embeddingHttpTimeout,
        @Min(0) @Max(100) int contextExpansionMaxChunks,
        @Min(1) @Max(32768) int answerReservedTokens
) {
    public RetrievalProperties(
            int parallelism,
            int queueCapacity,
            int vectorTopK,
            double vectorSimilarityThreshold,
            int lexicalLimit,
            int identifierLimit,
            int referenceLimit,
            int rrfK,
            int expansionSeeds,
            int expansionRadius,
            int expansionMax,
            int contextMaxTokens,
            int contextMaxChunks,
            int contextMaxChunksPerDocument,
            boolean rerankerEnabled,
            int rerankerCandidates,
            Duration rerankerTimeout,
            double rerankerFusedWeight
    ) {
        this(
                parallelism,
                queueCapacity,
                vectorTopK,
                vectorSimilarityThreshold,
                lexicalLimit,
                identifierLimit,
                referenceLimit,
                rrfK,
                expansionSeeds,
                expansionRadius,
                expansionMax,
                contextMaxTokens,
                contextMaxChunks,
                contextMaxChunksPerDocument,
                rerankerEnabled,
                rerankerCandidates,
                rerankerTimeout,
                rerankerFusedWeight,
                Duration.ofSeconds(8),
                Duration.ofSeconds(3),
                Duration.ofSeconds(20),
                Duration.ofSeconds(3),
                Math.min(3, contextMaxChunks),
                1024
        );
    }

    @ConstructorBinding
    public RetrievalProperties {
        validateDuration("reranker-timeout", rerankerTimeout, Duration.ofMillis(1), Duration.ofMinutes(1));
        validateDuration("request-timeout", requestTimeout, Duration.ofMillis(10), Duration.ofMinutes(2));
        validateDuration("strategy-timeout", strategyTimeout, Duration.ofMillis(10), Duration.ofMinutes(1));
        validateDuration("answer-timeout", answerTimeout, Duration.ofMillis(10), Duration.ofMinutes(5));
        validateDuration(
                "embedding-http-timeout",
                embeddingHttpTimeout,
                Duration.ofMillis(10),
                Duration.ofMinutes(1)
        );
        if (contextExpansionMaxChunks > contextMaxChunks) {
            throw new IllegalArgumentException(
                    "context-expansion-max-chunks must be <= context-max-chunks"
            );
        }
    }

    private static void validateDuration(
            String name,
            Duration value,
            Duration minimum,
            Duration maximum
    ) {
        if (value == null
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum
            );
        }
    }
}
