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
@ConfigurationProperties("akmai.self-optimizing.router-learning")
public record RouterLearningProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30d") @NotNull Duration lookback,
        @DefaultValue("20000") @Min(100) @Max(100000) int maxEvents,
        @DefaultValue("50") @Min(10) @Max(10000) int minDistinctQueriesPerClass,
        @DefaultValue("0.03")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double optionalLaneMinCitationSupport,
        @DefaultValue("0.10")
        @DecimalMin("0.0") @DecimalMax("1.0")
        double optionalLaneMinSelectedSupport
) {
    public RouterLearningProperties {
        if (lookback == null
                || lookback.compareTo(Duration.ofHours(1)) < 0
                || lookback.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException(
                    "router-learning lookback must be between 1h and 365d"
            );
        }
        if (optionalLaneMinCitationSupport > optionalLaneMinSelectedSupport) {
            throw new IllegalArgumentException(
                    "router-learning citation support threshold must be <= selected support threshold"
            );
        }
    }
}
