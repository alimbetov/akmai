package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.retention-economics")
public record RetentionEconomicsProperties(
        boolean enabled,
        @NotNull Duration fixedDelay
) {
    public RetentionEconomicsProperties {
        if (fixedDelay == null
                || fixedDelay.isZero()
                || fixedDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "retention-economics fixed-delay must be positive"
            );
        }
    }
}
