package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdbcTimeoutsTest {

    @Test
    void roundsPositiveDurationsUpToWholeSeconds() {
        assertThat(JdbcTimeouts.toSeconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(JdbcTimeouts.toSeconds(Duration.ofMillis(1000))).isEqualTo(1);
        assertThat(JdbcTimeouts.toSeconds(Duration.ofMillis(1001))).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> JdbcTimeouts.toSeconds(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcTimeouts.toSeconds(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
