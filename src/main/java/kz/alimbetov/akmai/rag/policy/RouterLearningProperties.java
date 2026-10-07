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
        double optionalLaneMinSelectedSupport,
        @DefaultValue("1d") @NotNull Duration reinforcementWindow,
        @DefaultValue("20") @Min(1) @Max(10000) int maxSamplesPerSourceWindow,
        @DefaultValue("3") @Min(1) @Max(1000) int minDistinctSourcesPerClass,
        @DefaultValue("0.60")
        @DecimalMin("0.05") @DecimalMax("1.0")
        double maxSourceShare
) {
    /**
     * Compatibility constructor for focused unit tests written before the
     * source-diversity gates existed. Spring uses the canonical constructor and
     * therefore the secure defaults above.
     */
    public RouterLearningProperties(
            boolean enabled,
            Duration lookback,
            int maxEvents,
            int minDistinctQueriesPerClass,
            double optionalLaneMinCitationSupport,
            double optionalLaneMinSelectedSupport
    ) {
        this(
                enabled,
                lookback,
                maxEvents,
                minDistinctQueriesPerClass,
                optionalLaneMinCitationSupport,
                optionalLaneMinSelectedSupport,
                lookback == null ? Duration.ofDays(1) : lookback,
                Math.max(1, maxEvents),
                1,
                1.0
        );
    }

    public RouterLearningProperties {
        if (lookback == null
                || lookback.compareTo(Duration.ofHours(1)) < 0
                || lookback.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException(
                    "router-learning lookback must be between 1h and 365d"
            );
        }
        if (reinforcementWindow == null
                || reinforcementWindow.compareTo(Duration.ofHours(1)) < 0
                || reinforcementWindow.compareTo(Duration.ofDays(30)) > 0
                || reinforcementWindow.compareTo(lookback) > 0) {
            throw new IllegalArgumentException(
                    "router-learning reinforcement-window must be between 1h and min(30d, lookback)"
            );
        }
        if (optionalLaneMinCitationSupport > optionalLaneMinSelectedSupport) {
            throw new IllegalArgumentException(
                    "router-learning citation support threshold must be <= selected support threshold"
            );
        }
        double minimumPossibleShare = 1.0 / minDistinctSourcesPerClass;
        if (maxSourceShare + 1.0e-12 < minimumPossibleShare) {
            throw new IllegalArgumentException(
                    "router-learning max-source-share is impossible for configured min-distinct-sources"
            );
        }
    }
}
