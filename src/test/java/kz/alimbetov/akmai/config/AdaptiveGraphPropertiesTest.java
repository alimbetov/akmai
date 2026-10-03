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
                new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                new AdaptiveGraphProperties.Storage(32)
        );

        assertThat(properties.quotas().total()).isEqualTo(32);
        assertThat(properties.expansionEnabled()).isFalse();
    }

    @Test
    void rejectsStorageShapeThatDoesNotMatchSchemaVersion() {
        assertThatThrownBy(() ->
                new AdaptiveGraphProperties(
                        false,
                        false,
                        false,
                        false,
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                        new AdaptiveGraphProperties.Storage(64)
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 32");
    }
}
