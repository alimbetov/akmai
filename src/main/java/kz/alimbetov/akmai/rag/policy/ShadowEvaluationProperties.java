package kz.alimbetov.akmai.rag.policy;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.self-optimizing.shadow-evaluation")
public record ShadowEvaluationProperties(
        @DefaultValue("100") @Min(10) @Max(100000) int minSamples,
        @DefaultValue("3") @Min(1) @Max(1000) int minDistinctSources,
        @DefaultValue("0.99")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double minDocumentEvidenceRecall,
        @DefaultValue("0.95")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double minChunkEvidenceRecall,
        @DefaultValue("0.02")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double maxFailureRate,
        @DefaultValue("15000") @Min(1) @Max(120000) long maxP95LatencyMs,
        @DefaultValue("5000") @Min(100) @Max(100000) int maxObservationsPerPolicy
) {
}
