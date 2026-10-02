package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.idempotency")
public record IdempotencyProperties(
        @NotNull Duration leaseDuration
) {
    public IdempotencyProperties {
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "idempotency lease-duration must be > 0 and <= 1h"
            );
        }
    }
}
