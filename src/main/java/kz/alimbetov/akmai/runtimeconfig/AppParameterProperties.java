package kz.alimbetov.akmai.runtimeconfig;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akmai.app-parameters")
public record AppParameterProperties(
        Duration cacheTtl,
        int cacheMaximumSize
) {

    public AppParameterProperties {
        if (cacheTtl == null
                || cacheTtl.isZero()
                || cacheTtl.isNegative()
                || cacheTtl.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(
                    "app-parameters cache-ttl must be in (0, 1m]"
            );
        }
        if (cacheMaximumSize < 8 || cacheMaximumSize > 4096) {
            throw new IllegalArgumentException(
                    "app-parameters cache-maximum-size must be in [8, 4096]"
            );
        }
    }
}
