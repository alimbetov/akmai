package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphReplayHarnessTest {

    private final GraphReplayHarness harness = new GraphReplayHarness();
    private final AdaptiveGraphThresholdCalibrator calibrator =
            new AdaptiveGraphThresholdCalibrator();

    @Test
    void corpusUsesVersionedUtilityContractAndBothReplaySplits() {
        GraphReplayHarness.Corpus corpus =
                harness.load("/quality/adaptive-graph-replay-v1.json");

        assertThat(corpus.corpusVersion())
                .isEqualTo("adaptive-graph-replay-v1");
        assertThat(corpus.utilityContractVersion())
                .isEqualTo(
                        AdaptiveGraphReplayUtility.CONTRACT_VERSION
                );
        assertThat(corpus.cases())
                .extracting(GraphReplayHarness.ReplayCase::split)
                .contains("CALIBRATION", "HOLDOUT");
        assertThat(corpus.thresholds())
                .containsExactly(0.60, 0.70, 0.80);
    }

    @Test
    void replaySelectsUsefulCompetitionThresholdAndValidatesHoldout() {
        GraphReplayHarness.Corpus corpus =
                harness.load("/quality/adaptive-graph-replay-v1.json");

        List<AdaptiveGraphThresholdCalibrator.ReplayObservation>
                calibrationObservations = harness.replay(
                        corpus,
                        "CALIBRATION",
                        corpus.thresholds()
                ).stream()
                .map(outcome ->
                        outcome.toCalibrationObservation(2.0)
                )
                .toList();

        AdaptiveGraphThresholdCalibrator.CalibrationReport report =
                calibrator.calibrate(
                        calibrationObservations,
                        corpus.policy().toCalibrationPolicy()
                );

        assertThat(report.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .CalibrationDecision.REPLAY_CANDIDATE
                );
        assertThat(report.selectedThreshold()).isNotNull();
        assertThat(report.selectedThreshold().threshold())
                .isEqualTo(0.70);

        AdaptiveGraphThresholdCalibrator.ThresholdEvaluation aggressive =
                report.evaluations().stream()
                        .filter(item -> item.threshold() == 0.60)
                        .findFirst()
                        .orElseThrow();
        assertThat(aggressive.safetyGatePassed()).isFalse();

        AdaptiveGraphThresholdCalibrator.ThresholdEvaluation strict =
                report.evaluations().stream()
                        .filter(item -> item.threshold() == 0.80)
                        .findFirst()
                        .orElseThrow();
        assertThat(strict.qualityGatePassed()).isFalse();

        List<AdaptiveGraphThresholdCalibrator.ReplayObservation>
                holdoutObservations = harness.replay(
                        corpus,
                        "HOLDOUT",
                        List.of(0.70)
                ).stream()
                .map(outcome ->
                        outcome.toCalibrationObservation(2.0)
                )
                .toList();

        AdaptiveGraphThresholdCalibrator.ReplayValidationReport validation =
                calibrator.validateReplay(
                        holdoutObservations,
                        0.70,
                        corpus.policy().toCalibrationPolicy()
                );

        assertThat(validation.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .ReplayDecision.QUALITY_GATE_CANDIDATE
                );
        assertThat(validation.evaluation().safetyGatePassed()).isTrue();
        assertThat(
                validation.evaluation().meanCompetitionUtilityDelta()
        ).isPositive();
    }

    @Test
    void replayKeepsAppendOnlyEffectSeparateFromCompetition() {
        GraphReplayHarness.Corpus corpus =
                harness.load("/quality/adaptive-graph-replay-v1.json");

        List<GraphReplayHarness.ReplayOutcome> outcomes =
                harness.replay(
                        corpus,
                        "CALIBRATION",
                        List.of(0.70)
                );

        assertThat(outcomes)
                .allSatisfy(outcome ->
                        assertThat(
                                outcome.utility()
                                        .appendOnlyUtilityDelta()
                        ).isZero()
                );
        assertThat(outcomes)
                .anySatisfy(outcome ->
                        assertThat(
                                outcome.utility()
                                        .competitionUtilityDelta()
                        ).isPositive()
                );
    }
}
