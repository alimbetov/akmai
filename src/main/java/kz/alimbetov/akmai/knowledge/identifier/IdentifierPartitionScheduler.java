package kz.alimbetov.akmai.knowledge.identifier;

import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "akmai.storage.partitions",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class IdentifierPartitionScheduler {

    private final IdentifierPartitionService partitionService;
    private final PartitionProperties properties;

    public IdentifierPartitionScheduler(
            IdentifierPartitionService partitionService,
            PartitionProperties properties
    ) {
        this.partitionService = partitionService;
        this.properties = properties;
    }

    @Scheduled(
            cron = "${akmai.storage.partitions.cron:0 15 2 * * *}",
            zone = "${akmai.storage.partitions.zone:UTC}"
    )
    public void ensurePartitions() {
        YearMonth current = YearMonth.now(ZoneId.of(properties.zone()));
        for (int offset = 0; offset <= properties.monthsAhead(); offset++) {
            partitionService.ensureMonth(current.plusMonths(offset));
        }
    }
}
