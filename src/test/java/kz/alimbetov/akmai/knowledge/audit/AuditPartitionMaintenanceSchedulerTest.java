package kz.alimbetov.akmai.knowledge.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditPartitionMaintenanceSchedulerTest {

    private static final String MAINTENANCE_SQL =
            "SELECT akmai_admin.maintain_audit_partitions()";

    @Test
    void startupAndScheduledTriggersDelegateToSameMaintenanceFunction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditPartitionMaintenanceScheduler scheduler =
                new AuditPartitionMaintenanceScheduler(jdbc);

        scheduler.maintainAtStartup();
        scheduler.maintain();

        verify(jdbc, times(2)).execute(MAINTENANCE_SQL);
    }

    @Test
    void databaseFailureRemainsVisibleToSchedulerInfrastructure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IllegalStateException failure = new IllegalStateException("db unavailable");
        doThrow(failure).when(jdbc).execute(MAINTENANCE_SQL);
        AuditPartitionMaintenanceScheduler scheduler =
                new AuditPartitionMaintenanceScheduler(jdbc);

        assertThatThrownBy(scheduler::maintain)
                .isSameAs(failure);
    }
}
