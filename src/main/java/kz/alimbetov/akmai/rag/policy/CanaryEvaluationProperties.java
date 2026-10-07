package kz.alimbetov.akmai.rag.policy;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.self-optimizing.canary-evaluation")
public record CanaryEvaluationProperties(
        @DefaultValue("5.0")
        @DecimalMin("0.1") @DecimalMax("50.0")
        double trafficPercent,
        @DefaultValue("100") @Min(10) @Max(100000) int minCanarySamples,
        @DefaultValue("300") @Min(10) @Max(100000) int minControlSamples,
        @DefaultValue("3") @Min(1) @Max(1000) int minDistinctSources,
        @DefaultValue("0.03")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double maxGroundedRateRegression,
        @DefaultValue("0.01")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double maxUnavailableRateRegression,
        @DefaultValue("0.01")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double maxCriticalFailureRate,
        @DefaultValue("0.05")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double maxDegradedRateRegression,
        @DefaultValue("0.20")
        @DecimalMin("0.0") @DecimalMax("5.0")
        double maxP95LatencyRegression,
        @DefaultValue("10000") @Min(100) @Max(100000) int maxObservationsPerPolicy
) {
}
