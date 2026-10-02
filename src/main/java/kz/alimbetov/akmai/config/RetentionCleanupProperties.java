package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.retention")
public record RetentionCleanupProperties(
        @NotNull Duration cleanupTransactionTimeout
) {
    public RetentionCleanupProperties {
        if (cleanupTransactionTimeout == null
                || cleanupTransactionTimeout.isZero()
                || cleanupTransactionTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "retention cleanup-transaction-timeout must be positive"
            );
        }
    }
}
