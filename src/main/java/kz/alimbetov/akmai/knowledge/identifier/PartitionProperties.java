package kz.alimbetov.akmai.knowledge.identifier;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akmai.storage.partitions")
public record PartitionProperties(
        boolean enabled,
        int monthsAhead,
        String cron,
        String zone
) {
    public PartitionProperties {
        if (monthsAhead < 0 || monthsAhead > 24) {
            throw new IllegalArgumentException("monthsAhead must be between 0 and 24");
        }
    }
}
