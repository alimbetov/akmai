package kz.alimbetov.akmai.rag.policy;

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
@ConfigurationProperties("akmai.self-optimizing.shadow-gate")
public record ShadowPolicyGateProperties(
        @DefaultValue("7d") @NotNull Duration lookback,
        @DefaultValue("100") @Min(10) @Max(100000) int minObservations,
        @DefaultValue("20") @Min(1) @Max(100000) int minChangedPlans,
        @DefaultValue("3") @Min(1) @Max(10000) int minDistinctSources,
        @DefaultValue("3") @Min(1) @Max(1000) int minQueryClasses,
        @DefaultValue("0.99")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double minChunkRetention,
        @DefaultValue("1.0")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double minDocumentRetention,
        @DefaultValue("0.05")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double maxFailureRate
) {
    public ShadowPolicyGateProperties {
        if (lookback == null
                || lookback.compareTo(Duration.ofHours(1)) < 0
                || lookback.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException(
                    "shadow-gate lookback must be between 1h and 90d"
            );
        }
        if (minChangedPlans > minObservations) {
            throw new IllegalArgumentException(
                    "shadow-gate min-changed-plans must be <= min-observations"
            );
        }
    }
}
