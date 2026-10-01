package kz.alimbetov.akmai.rag.retrieval;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
        @DecimalMin("0.0") @DecimalMax("1.0") double rerankerFusedWeight
) {
}
