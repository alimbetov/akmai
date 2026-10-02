package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.reembedding")
public record ReembeddingProperties(
        boolean autoMigrate,
        @NotNull Duration drainTimeout,
        @NotNull Duration pollInterval
) {
    public ReembeddingProperties {
        if (drainTimeout == null
                || drainTimeout.isZero()
                || drainTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "reembedding drain-timeout must be positive"
            );
        }
        if (pollInterval == null
                || pollInterval.isZero()
                || pollInterval.isNegative()
                || pollInterval.compareTo(drainTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "reembedding poll-interval must be positive and below drain-timeout"
            );
        }
    }
}
