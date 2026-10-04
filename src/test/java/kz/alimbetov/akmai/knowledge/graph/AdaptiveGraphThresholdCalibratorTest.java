package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptiveGraphThresholdCalibratorTest {

    private final AdaptiveGraphThresholdCalibrator calibrator =
            new AdaptiveGraphThresholdCalibrator();

    @Test
    void selectsLowestThresholdThatPassesMeasuredUtilityGate() {
        AdaptiveGraphThresholdCalibrator.CalibrationReport report =
                calibrator.calibrate(
                        List.of(
                                observation("q1", 0.40, -0.10, 3.0),
                                observation("q2", 0.40, -0.05, 3.0),
                                observation("q3", 0.40, 0.10, 4.0),
                                observation("q4", 0.40, 0.12, 5.0),
                                observation("q5", 0.40, 0.11, 4.0),
                                observation("q1", 0.50, 0.10, 4.0),
                                observation("q2", 0.50, 0.12, 5.0),
                                observation("q3", 0.50, 0.11, 4.0),
                                observation("q4", 0.50, 0.09, 4.0),
                                observation("q5", 0.50, 0.08, 5.0),
                                observation("q1", 0.60, 0.11, 4.0),
                                observation("q2", 0.60, 0.12, 5.0),
                                observation("q3", 0.60, 0.10, 4.0),
                                observation("q4", 0.60, 0.08, 4.0),
                                observation("q5", 0.60, 0.07, 5.0)
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
    }

    @Test
    void independentReplayMustPassBeforeCanaryEligibility() {
        AdaptiveGraphThresholdCalibrator.ReplayValidationReport passing =
                calibrator.validateReplay(
                        List.of(
                                observation("h1", 0.50, 0.10, 3.0),
                                observation("h2", 0.50, 0.08, 4.0),
                                observation("h3", 0.50, 0.09, 3.0),
                                observation("h4", 0.50, 0.11, 5.0),
                                observation("h5", 0.50, 0.07, 4.0)
                        ),
                        0.50,
                        policy()
                );

        AdaptiveGraphThresholdCalibrator.ReplayValidationReport failing =
                calibrator.validateReplay(
                        List.of(
                                observation("h1", 0.50, -0.12, 3.0),
                                observation("h2", 0.50, -0.08, 4.0),
                                observation("h3", 0.50, 0.02, 3.0),
                                observation("h4", 0.50, 0.03, 5.0),
                                observation("h5", 0.50, 0.01, 4.0)
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
    }

    @Test
    void safetyViolationBlocksOtherwiseUsefulThreshold() {
        AdaptiveGraphThresholdCalibrator.ReplayValidationReport report =
                calibrator.validateReplay(
                        List.of(
                                observation("h1", 0.50, 0.10, 3.0),
                                observation("h2", 0.50, 0.09, 3.0),
                                observation("h3", 0.50, 0.08, 4.0),
                                observation("h4", 0.50, 0.11, 4.0),
                                new AdaptiveGraphThresholdCalibrator
                                        .ReplayObservation(
                                                "h5",
                                                0.50,
                                                0.12,
                                                4.0,
                                                true
                                        )
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
                                observation("q1", 0.50, 0.10, 2.0),
                                observation("q2", 0.50, 0.12, 2.0),
                                observation("q1", 0.60, 0.11, 2.0),
                                observation("q2", 0.60, 0.13, 2.0)
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
    void rejectsDuplicateReplayKeysAtSameThreshold() {
        List<AdaptiveGraphThresholdCalibrator.ReplayObservation> observations =
                List.of(
                        observation("same-query", 0.50, 0.10, 2.0),
                        observation("same-query", 0.50, 0.11, 2.0)
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

    private AdaptiveGraphThresholdCalibrator.ReplayObservation observation(
            String replayKey,
            double threshold,
            double utilityDelta,
            double latencyDeltaMillis
    ) {
        return new AdaptiveGraphThresholdCalibrator.ReplayObservation(
                replayKey,
                threshold,
                utilityDelta,
                latencyDeltaMillis,
                false
        );
    }

    private AdaptiveGraphThresholdCalibrator.CalibrationPolicy policy() {
        return new AdaptiveGraphThresholdCalibrator.CalibrationPolicy(
                5,
                0.01,
                0.05,
                0.75,
                1.0,
                0.20,
                10.0
        );
    }
}
