package kz.alimbetov.akmai.knowledge.graph;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveGraphScoreCalculator {

    private final AdaptiveGraphProperties properties;

    public AdaptiveGraphScoreCalculator(AdaptiveGraphProperties properties) {
        this.properties = properties;
    }

    public ScoreDecision evaluate(
            AssociationBand currentBand,
            long distinctQuerySupport,
            long contextCount,
            long citationCount,
            Instant lastReinforcedAt,
            Instant now
    ) {
        if (currentBand == null) {
            throw new IllegalArgumentException(
                    "currentBand must not be null"
            );
        }
        if (lastReinforcedAt == null || now == null) {
            throw new IllegalArgumentException(
                    "maintenance timestamps must not be null"
            );
        }
        if (distinctQuerySupport < 0
                || contextCount < 0
                || citationCount < 0) {
            throw new IllegalArgumentException(
                    "association evidence must not be negative"
            );
        }

        AdaptiveGraphProperties.Scoring scoring = properties.scoring();
        double distinct = saturating(
                distinctQuerySupport,
                scoring.distinctQueryScale()
        );
        double context = saturating(
                contextCount,
                scoring.contextScale()
        );
        double citation = saturating(
                citationCount,
                scoring.citationScale()
        );

        double evidence = (
                scoring.distinctQueryWeight() * distinct
                        + scoring.contextWeight() * context
                        + scoring.citationWeight() * citation
        ) / scoring.totalEvidenceWeight();

        Duration age = Duration.between(lastReinforcedAt, now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        double halfLives = (double) age.toMillis()
                / scoring.halfLife().toMillis();
        double freshness = Math.pow(0.5, halfLives);
        double effectiveWeight = clamp(evidence * freshness);

        AssociationBand target = transition(
                currentBand,
                effectiveWeight,
                distinctQuerySupport,
                citationCount,
                age,
                scoring
        );
        return new ScoreDecision(effectiveWeight, target);
    }

    private AssociationBand transition(
            AssociationBand current,
            double weight,
            long distinctQuerySupport,
            long citationCount,
            Duration age,
            AdaptiveGraphProperties.Scoring scoring
    ) {
        return switch (current) {
            case CANDIDATE -> {
                if (weight >= scoring.promoteWarm()
                        && distinctQuerySupport
                        >= scoring.minimumDistinctQuerySupportWarm()) {
                    yield AssociationBand.WARM;
                }
                if (age.compareTo(scoring.candidateTtl()) >= 0) {
                    yield AssociationBand.DECAYED;
                }
                yield AssociationBand.CANDIDATE;
            }
            case WARM -> {
                if (weight >= scoring.promoteHot()
                        && distinctQuerySupport
                        >= scoring.minimumDistinctQuerySupportHot()
                        && citationCount
                        >= scoring.minimumCitationCountHot()) {
                    yield AssociationBand.HOT;
                }
                if (weight < scoring.demoteWarm()) {
                    yield AssociationBand.CANDIDATE;
                }
                yield AssociationBand.WARM;
            }
            case HOT -> weight < scoring.demoteHot()
                    ? AssociationBand.WARM
                    : AssociationBand.HOT;
            case DECAYED -> AssociationBand.DECAYED;
        };
    }

    private double saturating(long count, double scale) {
        return -Math.expm1(-((double) count / scale));
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record ScoreDecision(
            double effectiveWeight,
            AssociationBand targetBand
    ) {
    }
}
