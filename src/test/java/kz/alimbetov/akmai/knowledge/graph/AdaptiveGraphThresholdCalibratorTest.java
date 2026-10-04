package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptiveGraphThresholdCalibratorTest {

    private final AdaptiveGraphThresholdCalibrator calibrator =
            new AdaptiveGraphThresholdCalibrator();

    @Test
    void selectsLowestThresholdThatPassesLayeredUtilityGate() {
        AdaptiveGraphThresholdCalibrator.CalibrationReport report =
                calibrator.calibrate(
                        List.of(
                                observation("q1", 0.40, 0.00, -0.08, true),
                                observation("q2", 0.40, 0.00, -0.05, true),
                                observation("q3", 0.40, 0.00, 0.10, false),
                                observation("q4", 0.40, 0.00, 0.12, false),
                                observation("q5", 0.40, 0.00, 0.11, false),
                                observation("q1", 0.50, 0.00, 0.10, false),
                                observation("q2", 0.50, 0.00, 0.12, false),
                                observation("q3", 0.50, 0.00, 0.11, false),
                                observation("q4", 0.50, 0.00, 0.09, false),
                                observation("q5", 0.50, 0.00, 0.08, false),
                                observation("q1", 0.60, 0.00, 0.11, false),
                                observation("q2", 0.60, 0.00, 0.12, false),
                                observation("q3", 0.60, 0.00, 0.10, false),
                                observation("q4", 0.60, 0.00, 0.08, false),
                                observation("q5", 0.60, 0.00, 0.07, false)
                        ),
                        policy()
                );

        assertThat(report.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .CalibrationDecision.REPLAY_CANDIDATE
                );
        assertThat(report.selectedThreshold()).isNotNull();
        assertThat(report.selectedThreshold().threshold())
                .isEqualTo(0.50);
        assertThat(report.selectedThreshold().gatePassed()).isTrue();
        assertThat(report.selectedThreshold().meanOverallUtilityDelta())
                .isPositive();
        assertThat(report.selectedThreshold().meanCompetitionUtilityDelta())
                .isPositive();
        assertThat(report.targetParameter())
                .isEqualTo(
                        "akmai.adaptive-graph.competition.min-graph-score"
                );
    }

    @Test
    void independentReplayMustPassBeforeQualityGateCandidate() {
        AdaptiveGraphThresholdCalibrator.ReplayValidationReport passing =
                calibrator.validateReplay(
                        List.of(
                                observation("h1", 0.50, 0.01, 0.10, false),
                                observation("h2", 0.50, 0.01, 0.08, false),
                                observation("h3", 0.50, 0.01, 0.09, false),
                                observation("h4", 0.50, 0.01, 0.11, false),
                                observation("h5", 0.50, 0.01, 0.07, false)
                        ),
                        0.50,
                        policy()
                );

        AdaptiveGraphThresholdCalibrator.ReplayValidationReport failing =
                calibrator.validateReplay(
                        List.of(
                                observation("h1", 0.50, 0.01, -0.12, false),
                                observation("h2", 0.50, 0.01, -0.08, false),
                                observation("h3", 0.50, 0.01, 0.02, false),
                                observation("h4", 0.50, 0.01, 0.03, false),
                                observation("h5", 0.50, 0.01, 0.01, false)
                        ),
                        0.50,
                        policy()
                );

        assertThat(passing.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .ReplayDecision.QUALITY_GATE_CANDIDATE
                );
        assertThat(failing.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .ReplayDecision.REJECTED
                );
        assertThat(passing.targetParameter())
                .isEqualTo(
                        "akmai.adaptive-graph.competition.min-graph-score"
                );
    }

    @Test
    void harmfulAppendOnlyGraphPathBlocksCompetitionThreshold() {
        AdaptiveGraphThresholdCalibrator.ReplayValidationReport report =
                calibrator.validateReplay(
                        List.of(
                                observation("h1", 0.50, -0.10, 0.20, false),
                                observation("h2", 0.50, -0.10, 0.20, false),
                                observation("h3", 0.50, -0.10, 0.20, false),
                                observation("h4", 0.50, -0.10, 0.20, false),
                                observation("h5", 0.50, -0.10, 0.20, false)
                        ),
                        0.50,
                        policy()
                );

        assertThat(report.evaluation().meanOverallUtilityDelta())
                .isPositive();
        assertThat(report.evaluation().meanCompetitionUtilityDelta())
                .isPositive();
        assertThat(report.evaluation().meanAppendOnlyUtilityDelta())
                .isNegative();
        assertThat(report.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .ReplayDecision.REJECTED
                );
    }

    @Test
    void safetyViolationBlocksOtherwiseUsefulThreshold() {
        AdaptiveGraphThresholdCalibrator.ReplayValidationReport report =
                calibrator.validateReplay(
                        List.of(
                                observation("h1", 0.50, 0.00, 0.10, false),
                                observation("h2", 0.50, 0.00, 0.09, false),
                                observation("h3", 0.50, 0.00, 0.08, false),
                                observation("h4", 0.50, 0.00, 0.11, false),
                                observation("h5", 0.50, 0.00, 0.12, true)
                        ),
                        0.50,
                        policy()
                );

        assertThat(report.evaluation().qualityGatePassed()).isTrue();
        assertThat(report.evaluation().safetyGatePassed()).isFalse();
        assertThat(report.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .ReplayDecision.REJECTED
                );
    }

    @Test
    void reportsInsufficientDataPerThreshold() {
        AdaptiveGraphThresholdCalibrator.CalibrationReport report =
                calibrator.calibrate(
                        List.of(
                                observation("q1", 0.50, 0.00, 0.10, false),
                                observation("q2", 0.50, 0.00, 0.12, false),
                                observation("q1", 0.60, 0.00, 0.11, false),
                                observation("q2", 0.60, 0.00, 0.13, false)
                        ),
                        policy()
                );

        assertThat(report.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .CalibrationDecision.INSUFFICIENT_DATA
                );
        assertThat(report.selectedThreshold()).isNull();
    }

    @Test
    void rejectsThresholdSweepWithDifferentRequestCohorts() {
        List<AdaptiveGraphThresholdCalibrator.ReplayObservation> observations =
                List.of(
                        observation("q1", 0.50, 0.00, 0.10, false),
                        observation("q2", 0.50, 0.00, 0.11, false),
                        observation("q1", 0.60, 0.00, 0.10, false),
                        observation("q3", 0.60, 0.00, 0.11, false)
                );

        assertThatThrownBy(() ->
                calibrator.calibrate(observations, policy())
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same replayKey cohort");
    }

    @Test
    void rejectsDuplicateReplayKeysAtSameThreshold() {
        List<AdaptiveGraphThresholdCalibrator.ReplayObservation> observations =
                List.of(
                        observation("same-query", 0.50, 0.00, 0.10, false),
                        observation("same-query", 0.50, 0.00, 0.11, false)
                );

        assertThatThrownBy(() ->
                calibrator.evaluateAtThreshold(
                        observations,
                        0.50,
                        policy()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate replayKey");
    }

    @Test
    void rejectsInconsistentLayeredUtilityDeltas() {
        assertThatThrownBy(() ->
                new AdaptiveGraphThresholdCalibrator.ReplayObservation(
                        "q",
                        0.50,
                        0.50,
                        0.10,
                        0.10,
                        2.0,
                        false
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overall utility delta");
    }

    @Test
    void targetsCompetitionMinGraphScore() {
        assertThat(AdaptiveGraphThresholdCalibrator.TARGET_PARAMETER)
                .isEqualTo(
                        "akmai.adaptive-graph.competition.min-graph-score"
                );
    }

    private AdaptiveGraphThresholdCalibrator.ReplayObservation observation(
            String replayKey,
            double threshold,
            double appendOnlyDelta,
            double competitionDelta,
            boolean safetyViolation
    ) {
        return new AdaptiveGraphThresholdCalibrator.ReplayObservation(
                replayKey,
                threshold,
                appendOnlyDelta + competitionDelta,
                appendOnlyDelta,
                competitionDelta,
                2.0,
                safetyViolation
        );
    }

    private AdaptiveGraphThresholdCalibrator.CalibrationPolicy policy() {
        return new AdaptiveGraphThresholdCalibrator.CalibrationPolicy(
                5,
                0.01,
                0.00,
                0.05,
                0.05,
                0.75,
                1.0,
                0.20,
                10.0
        );
    }
}
