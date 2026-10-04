package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptiveGraphReplayQualityGateTest {

    private final GraphReplayHarness harness = new GraphReplayHarness();
    private final AdaptiveGraphThresholdCalibrator calibrator =
            new AdaptiveGraphThresholdCalibrator();

    @Test
    void emitsVersionedReplayCalibrationReport() throws Exception {
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

        AdaptiveGraphThresholdCalibrator.CalibrationReport calibration =
                calibrator.calibrate(
                        calibrationObservations,
                        corpus.policy().toCalibrationPolicy()
                );

        assertThat(calibration.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .CalibrationDecision.REPLAY_CANDIDATE
                );
        assertThat(calibration.selectedThreshold()).isNotNull();

        double selectedThreshold =
                calibration.selectedThreshold().threshold();

        List<AdaptiveGraphThresholdCalibrator.ReplayObservation>
                holdoutObservations = harness.replay(
                        corpus,
                        "HOLDOUT",
                        List.of(selectedThreshold)
                ).stream()
                .map(outcome ->
                        outcome.toCalibrationObservation(2.0)
                )
                .toList();

        AdaptiveGraphThresholdCalibrator.ReplayValidationReport holdout =
                calibrator.validateReplay(
                        holdoutObservations,
                        selectedThreshold,
                        corpus.policy().toCalibrationPolicy()
                );

        assertThat(holdout.decision())
                .isEqualTo(
                        AdaptiveGraphThresholdCalibrator
                                .ReplayDecision.QUALITY_GATE_CANDIDATE
                );

        ReplayReport report = new ReplayReport(
                "adaptive-graph-replay-report-v1",
                corpus.corpusVersion(),
                corpus.utilityContractVersion(),
                calibration.targetParameter(),
                selectedThreshold,
                calibration.decision().name(),
                holdout.decision().name(),
                calibration.evaluations(),
                holdout.evaluation()
        );

        Path output = Path.of(
                "target",
                "quality",
                "adaptive-graph-replay-report.json"
        );
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);

        assertThat(output).exists();
    }

    record ReplayReport(
            String reportVersion,
            String corpusVersion,
            String utilityContractVersion,
            String targetParameter,
            double selectedThreshold,
            String calibrationDecision,
            String holdoutDecision,
            List<AdaptiveGraphThresholdCalibrator.ThresholdEvaluation>
                    calibrationEvaluations,
            AdaptiveGraphThresholdCalibrator.ThresholdEvaluation
                    holdoutEvaluation
    ) {
    }
}
