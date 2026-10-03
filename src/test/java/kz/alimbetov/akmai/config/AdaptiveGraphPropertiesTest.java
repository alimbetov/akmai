package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdaptiveGraphPropertiesTest {

    @Test
    void acceptsBoundedBandQuotasAndFixedStorageShape() {
        AdaptiveGraphProperties properties = new AdaptiveGraphProperties(
                false,
                false,
                false,
                false,
                1,
                new AdaptiveGraphProperties.Learning(8, 32, ""),
                new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
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
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                        new AdaptiveGraphProperties.Storage(32)
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint secret");
    }

    @Test
    void rejectsStorageShapeThatDoesNotMatchSchemaVersion() {
        assertThatThrownBy(() ->
                new AdaptiveGraphProperties(
                        false,
                        false,
                        false,
                        false,
                        1,
                        new AdaptiveGraphProperties.Learning(8, 32, ""),
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                        new AdaptiveGraphProperties.Storage(64)
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 32");
    }
}
