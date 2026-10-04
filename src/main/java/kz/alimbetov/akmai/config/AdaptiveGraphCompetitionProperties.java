package kz.alimbetov.akmai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.adaptive-graph.competition")
public record AdaptiveGraphCompetitionProperties(
        boolean enabled,
        int maxPromotions,
        int protectedBasePrefix,
        double minGraphScore
) {

    @ConstructorBinding
    public AdaptiveGraphCompetitionProperties {
        if (maxPromotions < 1 || maxPromotions > 2) {
            throw new IllegalArgumentException(
                    "adaptive-graph competition maxPromotions must be in [1, 2]"
            );
        }
        if (protectedBasePrefix < 1 || protectedBasePrefix > 32) {
            throw new IllegalArgumentException(
                    "adaptive-graph competition protectedBasePrefix "
                            + "must be in [1, 32]"
            );
        }
        if (!Double.isFinite(minGraphScore)
                || minGraphScore < 0
                || minGraphScore > 1) {
            throw new IllegalArgumentException(
                    "adaptive-graph competition minGraphScore must be in [0, 1]"
            );
        }
    }
}
