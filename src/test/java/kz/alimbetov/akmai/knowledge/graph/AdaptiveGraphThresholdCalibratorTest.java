package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

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
                                observation(0.30, -0.10, 3.0),
                                observation(0.40, -0.05, 3.0),
                                observation(0.50, 0.10, 4.0),
                                observation(0.60, 0.12, 5.0),
                                observation(0.70, 0.11, 4.0),
                                observation(0.80, 0.09, 4.0),
                                observation(0.90, 0.08, 5.0)
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
        AdaptiveGraphThresholdCalibrator.ReplayGateReport passing =
                calibrator.validateReplay(
                        List.of(
                                observation(0.52, 0.10, 3.0),
                                observation(0.60, 0.08, 4.0),
                                observation(0.70, 0.09, 3.0),
                                observation(0.80, 0.11, 5.0),
                                observation(0.90, 0.07, 4.0)
                        ),
                        0.50,
                        policy()
                );

        AdaptiveGraphThresholdCalibrator.ReplayGateReport failing =
                calibrator.validateReplay(
                        List.of(
                                observation(0.52, -0.12, 3.0),
                                observation(0.60, -0.08, 4.0),
                                observation(0.70, 0.02, 3.0),
                                observation(0.80, 0.03, 5.0),
                                observation(0.90, 0.01, 4.0)
                        ),
                        0.50,
                        policy()
                );

        assertThat(passing.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .GateDecision.CANARY_ELIGIBLE
                );
        assertThat(failing.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .GateDecision.REJECTED
                );
    }

    @Test
    void safetyViolationBlocksOtherwiseUsefulThreshold() {
        AdaptiveGraphThresholdCalibrator.ReplayGateReport report =
                calibrator.validateReplay(
                        List.of(
                                observation(0.55, 0.10, 3.0),
                                observation(0.60, 0.09, 3.0),
                                observation(0.70, 0.08, 4.0),
                                observation(0.80, 0.11, 4.0),
                                new AdaptiveGraphThresholdCalibrator
                                        .ReplayObservation(
                                                0.90,
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
                                .GateDecision.REJECTED
                );
    }

    @Test
    void reportsInsufficientDataWithoutInventingConfidence() {
        AdaptiveGraphThresholdCalibrator.CalibrationReport report =
                calibrator.calibrate(
                        List.of(
                                observation(0.80, 0.10, 2.0),
                                observation(0.90, 0.12, 2.0)
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

    private AdaptiveGraphThresholdCalibrator.ReplayObservation observation(
            double score,
            double utilityDelta,
            double latencyDeltaMillis
    ) {
        return new AdaptiveGraphThresholdCalibrator.ReplayObservation(
                score,
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
