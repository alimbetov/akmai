package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.reconciliation")
public record ReconciliationProperties(
        boolean enabled,
        @Min(1) @Max(1000) int batchSize,
        @Min(1) @Max(100) int maxBatchesPerRun,
        @NotNull Duration gracePeriod,
        @NotNull Duration fixedDelay
) {
    public ReconciliationProperties {
        if (gracePeriod == null || gracePeriod.isNegative()) {
            throw new IllegalArgumentException(
                    "reconciliation grace-period must not be negative"
            );
        }
        if (fixedDelay == null
                || fixedDelay.isZero()
                || fixedDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "reconciliation fixed-delay must be positive"
            );
        }
    }
}
