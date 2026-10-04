package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdaptiveGraphPropertiesTest {

    @Test
    void acceptsBoundedBandQuotasAndFixedStorageShape() {
        AdaptiveGraphProperties properties = properties(
                false,
                new AdaptiveGraphProperties.Storage(32)
        );

        assertThat(properties.quotas().total()).isEqualTo(32);
        assertThat(properties.expansionEnabled()).isFalse();
        assertThat(properties.learning().toString())
                .contains("<redacted>")
                .doesNotContain("0123456789abcdef");
    }

    @Test
    void learningRequiresStrongFingerprintSecret() {
        assertThatThrownBy(() ->
                new AdaptiveGraphProperties(
                        true,
                        false,
                        false,
                        false,
                        1,
                        new AdaptiveGraphProperties.Learning(
                                8,
                                32,
                                "too-short"
                        ),
                        shadowExpansion(),
                        scoring(),
                        maintenance(),
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                        new AdaptiveGraphProperties.Storage(32)
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint secret");
    }

    @Test
    void rejectsInvalidHysteresisOrdering() {
        assertThatThrownBy(() ->
                new AdaptiveGraphProperties.Scoring(
                        0.45,
                        0.20,
                        0.35,
                        4.0,
                        4.0,
                        2.0,
                        Duration.ofDays(30),
                        Duration.ofHours(1),
                        Duration.ofDays(30),
                        Duration.ofDays(14),
                        0.35,
                        0.20,
                        0.45,
                        0.50,
                        2,
                        4,
                        1
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hysteresis");
    }

    @Test
    void rejectsHotSupportGateBelowWarmSupportGate() {
        assertThatThrownBy(() ->
                new AdaptiveGraphProperties.Scoring(
                        0.45,
                        0.20,
                        0.35,
                        4.0,
                        4.0,
                        2.0,
                        Duration.ofDays(30),
                        Duration.ofHours(1),
                        Duration.ofDays(30),
                        Duration.ofDays(14),
                        0.35,
                        0.20,
                        0.65,
                        0.50,
                        4,
                        2,
                        1
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumDistinctQuerySupportHot");
    }

    @Test
    void rejectsStorageShapeThatDoesNotMatchSchemaVersion() {
        assertThatThrownBy(() ->
                properties(
                        false,
                        new AdaptiveGraphProperties.Storage(64)
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 32");
    }

    private AdaptiveGraphProperties properties(
            boolean learning,
            AdaptiveGraphProperties.Storage storage
    ) {
        return new AdaptiveGraphProperties(
                learning,
                false,
                false,
                false,
                1,
                new AdaptiveGraphProperties.Learning(
                        8,
                        32,
                        learning
                                ? "0123456789abcdef0123456789abcdef"
                                : ""
                ),
                shadowExpansion(),
                scoring(),
                maintenance(),
                new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                storage
        );
    }

    private AdaptiveGraphProperties.ShadowExpansion shadowExpansion() {
        return new AdaptiveGraphProperties.ShadowExpansion(
                4,
                4,
                2,
                12,
                0.25,
                0.50,
                0.30,
                1.0,
                0.70
        );
    }

    private AdaptiveGraphProperties.Scoring scoring() {
        return new AdaptiveGraphProperties.Scoring(
                0.45,
                0.20,
                0.35,
                4.0,
                4.0,
                2.0,
                Duration.ofDays(30),
                Duration.ofHours(1),
                Duration.ofDays(30),
                Duration.ofDays(14),
                0.35,
                0.20,
                0.65,
                0.50,
                2,
                4,
                1
        );
    }

    private AdaptiveGraphProperties.Maintenance maintenance() {
        return new AdaptiveGraphProperties.Maintenance(
                100,
                10,
                Duration.ofMinutes(5)
        );
    }
}
