package kz.alimbetov.akmai.knowledge.identifier;

import java.time.YearMonth;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdentifierPartitionScheduler {

    private final IdentifierPartitionService partitionService;

    public IdentifierPartitionScheduler(IdentifierPartitionService partitionService) {
        this.partitionService = partitionService;
    }

    @Scheduled(cron = "0 15 2 * * *", zone = "UTC")
    public void ensurePartitions() {
        YearMonth current = YearMonth.now();
        partitionService.ensureMonth(current);
        partitionService.ensureMonth(current.plusMonths(1));
        partitionService.ensureMonth(current.plusMonths(2));
    }
}
