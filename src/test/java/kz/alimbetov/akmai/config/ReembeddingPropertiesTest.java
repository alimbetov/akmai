package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReembeddingPropertiesTest {

    @Test
    void legacyConstructorDerivesHeartbeatSafelyFromShortLease() {
        ReembeddingProperties properties = new ReembeddingProperties(
                false,
                Duration.ofSeconds(2),
                Duration.ofMillis(10),
                Duration.ofSeconds(1)
        );

        assertThat(properties.heartbeatInterval())
                .isEqualTo(Duration.ofMillis(250));
        assertThat(properties.heartbeatInterval())
                .isLessThan(properties.leaseDuration().dividedBy(2));
    }

    @Test
    void rejectsHeartbeatAtHalfLeaseBoundary() {
        assertThatThrownBy(() -> new ReembeddingProperties(
                false,
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                Duration.ofSeconds(4),
                Duration.ofSeconds(2)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat-interval");
    }

    @Test
    void acceptsHeartbeatStrictlyBelowHalfLease() {
        ReembeddingProperties properties = new ReembeddingProperties(
                false,
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                Duration.ofSeconds(4),
                Duration.ofSeconds(1)
        );

        assertThat(properties.heartbeatInterval())
                .isEqualTo(Duration.ofSeconds(1));
    }
}
