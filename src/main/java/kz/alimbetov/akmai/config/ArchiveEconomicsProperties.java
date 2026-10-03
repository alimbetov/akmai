package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.archive-economics")
public record ArchiveEconomicsProperties(
        boolean enabled,
        @NotNull Duration fixedDelay
) {
    public ArchiveEconomicsProperties {
        if (fixedDelay == null
                || fixedDelay.isZero()
                || fixedDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "archive-economics fixed-delay must be positive"
            );
        }
    }
}
