package kz.alimbetov.akmai.knowledge.audit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuditPartitionMaintenanceScheduler {

    private final JdbcTemplate jdbcTemplate;

    public AuditPartitionMaintenanceScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void maintainAtStartup() {
        maintain();
    }

    @Scheduled(
            cron = "${akmai.audit.partition-maintenance-cron:0 15 2 * * *}",
            zone = "${akmai.audit.zone:Asia/Almaty}"
    )
    public void maintain() {
        jdbcTemplate.execute(
                "SELECT akmai_admin.maintain_audit_partitions()"
        );
    }
}
