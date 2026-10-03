package kz.alimbetov.akmai.knowledge.graph;

import java.time.Duration;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;

final class AdaptiveGraphTestProperties {

    private AdaptiveGraphTestProperties() {
    }

    static AdaptiveGraphProperties create(
            AdaptiveGraphProperties.BandQuotas quotas
    ) {
        return new AdaptiveGraphProperties(
                false,
                false,
                false,
                false,
                1,
                new AdaptiveGraphProperties.Learning(8, 32, ""),
                new AdaptiveGraphProperties.ShadowExpansion(
                        4,
                        4,
                        2,
                        12,
                        0.25,
                        0.50,
                        0.30,
                        1.0,
                        0.70
                ),
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
                        0.50
                ),
                new AdaptiveGraphProperties.Maintenance(
                        100,
                        10,
                        Duration.ofMinutes(5)
                ),
                quotas,
                new AdaptiveGraphProperties.Storage(32)
        );
    }
}
