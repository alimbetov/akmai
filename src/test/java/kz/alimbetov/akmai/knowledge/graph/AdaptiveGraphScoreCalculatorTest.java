package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.junit.jupiter.api.Test;

class AdaptiveGraphScoreCalculatorTest {

    @Test
    void promotesCandidateToWarmAndUsesFreshnessDecay() {
        AdaptiveGraphScoreCalculator calculator =
                new AdaptiveGraphScoreCalculator(properties());

        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        AdaptiveGraphScoreCalculator.ScoreDecision fresh =
                calculator.evaluate(
                        AssociationBand.CANDIDATE,
                        8,
                        8,
                        4,
                        now,
                        now
                );
        AdaptiveGraphScoreCalculator.ScoreDecision stale =
                calculator.evaluate(
                        AssociationBand.CANDIDATE,
                        8,
                        8,
                        4,
                        now.minus(Duration.ofDays(30)),
                        now
                );

        assertThat(fresh.targetBand()).isEqualTo(AssociationBand.WARM);
        assertThat(fresh.effectiveWeight())
                .isGreaterThan(stale.effectiveWeight());
        assertThat(stale.effectiveWeight())
                .isCloseTo(
                        fresh.effectiveWeight() * 0.5,
                        org.assertj.core.data.Offset.offset(0.01)
                );
    }

    @Test
    void appliesHysteresisInsteadOfOscillatingAtOneThreshold() {
        AdaptiveGraphScoreCalculator calculator =
                new AdaptiveGraphScoreCalculator(properties());
        Instant now = Instant.parse("2026-10-03T00:00:00Z");

        AdaptiveGraphScoreCalculator.ScoreDecision warm =
                calculator.evaluate(
                        AssociationBand.WARM,
                        4,
                        4,
                        1,
                        now,
                        now
                );

        assertThat(warm.effectiveWeight()).isBetween(0.20, 0.65);
        assertThat(warm.targetBand()).isEqualTo(AssociationBand.WARM);
    }

    @Test
    void contextCountContributesBoundedEvidence() {
        AdaptiveGraphScoreCalculator calculator =
                new AdaptiveGraphScoreCalculator(properties());
        Instant now = Instant.parse("2026-10-03T00:00:00Z");

        AdaptiveGraphScoreCalculator.ScoreDecision withoutContext =
                calculator.evaluate(
                        AssociationBand.CANDIDATE,
                        4,
                        0,
                        1,
                        now,
                        now
                );
        AdaptiveGraphScoreCalculator.ScoreDecision withContext =
                calculator.evaluate(
                        AssociationBand.CANDIDATE,
                        4,
                        4,
                        1,
                        now,
                        now
                );
        AdaptiveGraphScoreCalculator.ScoreDecision withLargeContext =
                calculator.evaluate(
                        AssociationBand.CANDIDATE,
                        4,
                        100,
                        1,
                        now,
                        now
                );

        assertThat(withContext.effectiveWeight())
                .isGreaterThan(withoutContext.effectiveWeight());
        assertThat(withContext.effectiveWeight())
                .isCloseTo(
                        0.5485926323391408,
                        org.assertj.core.data.Offset.offset(1.0e-12)
                );
        assertThat(withLargeContext.effectiveWeight())
                .isGreaterThanOrEqualTo(withContext.effectiveWeight());
        assertThat(withLargeContext.effectiveWeight())
                .isLessThanOrEqualTo(1.0);
    }

    @Test
    void requiresIndependentQuerySupportBeforeWarmPromotion() {
        AdaptiveGraphScoreCalculator calculator =
                new AdaptiveGraphScoreCalculator(properties());
        Instant now = Instant.parse("2026-10-03T00:00:00Z");

        AdaptiveGraphScoreCalculator.ScoreDecision decision =
                calculator.evaluate(
                        AssociationBand.CANDIDATE,
                        1,
                        100,
                        100,
                        now,
                        now
                );

        assertThat(decision.effectiveWeight()).isGreaterThan(0.35);
        assertThat(decision.targetBand())
                .isEqualTo(AssociationBand.CANDIDATE);
    }

    @Test
    void requiresCitationEvidenceBeforeHotPromotion() {
        AdaptiveGraphProperties base = properties();
        AdaptiveGraphProperties.Scoring scoring =
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
                        0.55,
                        0.50,
                        2,
                        4,
                        1
                );
        AdaptiveGraphProperties guarded = new AdaptiveGraphProperties(
                base.learningEnabled(),
                base.maintenanceEnabled(),
                base.shadowExpansionEnabled(),
                base.expansionEnabled(),
                base.graphVersion(),
                base.learning(),
                base.shadowExpansion(),
                scoring,
                base.maintenance(),
                base.quotas(),
                base.storage()
        );
        AdaptiveGraphScoreCalculator calculator =
                new AdaptiveGraphScoreCalculator(guarded);
        Instant now = Instant.parse("2026-10-03T00:00:00Z");

        AdaptiveGraphScoreCalculator.ScoreDecision decision =
                calculator.evaluate(
                        AssociationBand.WARM,
                        100,
                        100,
                        0,
                        now,
                        now
                );

        assertThat(decision.effectiveWeight()).isGreaterThan(0.55);
        assertThat(decision.targetBand())
                .isEqualTo(AssociationBand.WARM);
    }

    @Test
    void decaysOldUnprovenCandidate() {
        AdaptiveGraphScoreCalculator calculator =
                new AdaptiveGraphScoreCalculator(properties());
        Instant now = Instant.parse("2026-10-03T00:00:00Z");

        AdaptiveGraphScoreCalculator.ScoreDecision decision =
                calculator.evaluate(
                        AssociationBand.CANDIDATE,
                        1,
                        1,
                        0,
                        now.minus(Duration.ofDays(31)),
                        now
                );

        assertThat(decision.targetBand())
                .isEqualTo(AssociationBand.DECAYED);
    }

    private AdaptiveGraphProperties properties() {
        return AdaptiveGraphTestProperties.create(
                new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
        );
    }
}
