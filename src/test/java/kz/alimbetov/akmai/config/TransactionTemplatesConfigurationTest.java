package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

class TransactionTemplatesConfigurationTest {

    private final TransactionTemplatesConfiguration configuration =
            new TransactionTemplatesConfiguration();
    private final PlatformTransactionManager manager =
            mock(PlatformTransactionManager.class);

    @Test
    void cleanupTransactionMustFinishBeforeHalfOfLease() {
        RetentionProperties retention = retention(Duration.ofMinutes(10));

        assertThatThrownBy(() ->
                configuration.cleanupTransactionTemplate(
                        manager,
                        retention,
                        new RetentionCleanupProperties(Duration.ofMinutes(5))
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("less than half");

        var template = configuration.cleanupTransactionTemplate(
                manager,
                retention,
                new RetentionCleanupProperties(Duration.ofMinutes(4))
        );
        assertThat(template.getTimeout()).isEqualTo(240);
    }

    @Test
    void repairTransactionUsesRequiresNewBoundary() {
        var template = configuration.repairTransactionTemplate(
                manager,
                new RetentionCleanupProperties(Duration.ofMinutes(2))
        );

        assertThat(template.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(template.getTimeout()).isEqualTo(120);
    }

    private RetentionProperties retention(Duration lease) {
        return new RetentionProperties(
                true,
                "0 0 * * * *",
                "UTC",
                10,
                2,
                3,
                2,
                2,
                lease,
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
    }
}
