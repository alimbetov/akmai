package kz.alimbetov.akmai.knowledge.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Offline/shadow-only threshold calibration for adaptive graph serving.
 *
 * <p>The calibrator is deliberately advisory. It never mutates runtime
 * configuration and never promotes a graph version by itself.</p>
 */
public final class AdaptiveGraphThresholdCalibrator {

    public CalibrationReport calibrate(
            List<ReplayObservation> calibrationObservations,
            CalibrationPolicy policy
    ) {
        Objects.requireNonNull(
                calibrationObservations,
                "calibrationObservations must not be null"
        );
        Objects.requireNonNull(policy, "policy must not be null");

        if (calibrationObservations.size() < policy.minimumSamples()) {
            return new CalibrationReport(
                    CalibrationDecision.INSUFFICIENT_DATA,
                    null,
                    List.of()
            );
        }

        List<Double> thresholds = calibrationObservations.stream()
                .map(ReplayObservation::graphScore)
                .distinct()
                .sorted()
                .toList();

        List<ThresholdEvaluation> evaluations =
                new ArrayList<>(thresholds.size());
        ThresholdEvaluation selected = null;

        for (double threshold : thresholds) {
            ThresholdEvaluation evaluation = evaluateAtThreshold(
                    calibrationObservations,
                    threshold,
                    policy
            );
            evaluations.add(evaluation);
            if (selected == null && evaluation.gatePassed()) {
                selected = evaluation;
            }
        }

        if (selected == null) {
            return new CalibrationReport(
                    CalibrationDecision.REJECTED,
                    null,
                    evaluations
            );
        }

        return new CalibrationReport(
                CalibrationDecision.REPLAY_CANDIDATE,
                selected,
                evaluations
        );
    }

    public ReplayGateReport validateReplay(
            List<ReplayObservation> replayObservations,
            double candidateThreshold,
            CalibrationPolicy policy
    ) {
        Objects.requireNonNull(
                replayObservations,
                "replayObservations must not be null"
        );
        Objects.requireNonNull(policy, "policy must not be null");
        requireUnitInterval("candidateThreshold", candidateThreshold);

        ThresholdEvaluation evaluation = evaluateAtThreshold(
                replayObservations,
                candidateThreshold,
                policy
        );

        GateDecision decision;
        if (evaluation.sampleCount() < policy.minimumSamples()) {
            decision = GateDecision.INSUFFICIENT_DATA;
        } else if (evaluation.gatePassed()) {
            decision = GateDecision.CANARY_ELIGIBLE;
        } else {
            decision = GateDecision.REJECTED;
        }

        return new ReplayGateReport(decision, evaluation);
    }

    public ThresholdEvaluation evaluateAtThreshold(
            List<ReplayObservation> observations,
            double threshold,
            CalibrationPolicy policy
    ) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        requireUnitInterval("threshold", threshold);

        List<ReplayObservation> included = observations.stream()
                .filter(observation -> observation.graphScore() >= threshold)
                .toList();

        int sampleCount = included.size();
        if (sampleCount == 0) {
            return new ThresholdEvaluation(
                    threshold,
                    0,
                    0,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    true,
                    false
            );
        }

        int benefitCount = 0;
        int regressionCount = 0;
        boolean safetyGatePassed = true;
        double utilityTotal = 0.0;
        List<Double> latencyDeltas = new ArrayList<>(sampleCount);

        for (ReplayObservation observation : included) {
            utilityTotal += observation.utilityDelta();
            latencyDeltas.add(observation.latencyDeltaMillis());
            if (observation.safetyViolation()) {
                safetyGatePassed = false;
            }
            if (observation.utilityDelta()
                    >= policy.minimumMeaningfulUtilityDelta()) {
                benefitCount++;
            } else if (observation.utilityDelta()
                    <= -policy.minimumMeaningfulUtilityDelta()) {
                regressionCount++;
            }
        }

        double meanUtilityDelta = utilityTotal / sampleCount;
        double benefitProbability = (double) benefitCount / sampleCount;
        double benefitProbabilityLowerBound = wilsonLowerBound(
                benefitCount,
                sampleCount,
                policy.confidenceZ()
        );
        double regressionRate = (double) regressionCount / sampleCount;
        double p95LatencyDeltaMillis = percentile95(latencyDeltas);

        boolean qualityGatePassed =
                sampleCount >= policy.minimumSamples()
                        && meanUtilityDelta
                        >= policy.minimumMeanUtilityLift()
                        && benefitProbabilityLowerBound
                        >= policy.minimumBenefitProbabilityLowerBound()
                        && regressionRate
                        <= policy.maximumRegressionRate()
                        && p95LatencyDeltaMillis
                        <= policy.maximumP95LatencyRegressionMillis();

        return new ThresholdEvaluation(
                threshold,
                sampleCount,
                benefitCount,
                regressionCount,
                meanUtilityDelta,
                benefitProbability,
                benefitProbabilityLowerBound,
                regressionRate,
                p95LatencyDeltaMillis,
                safetyGatePassed,
                qualityGatePassed
        );
    }

    private double wilsonLowerBound(
            int successes,
            int trials,
            double z
    ) {
        if (trials <= 0) {
            return 0.0;
        }
        double n = trials;
        double probability = successes / n;
        double zSquared = z * z;
        double denominator = 1.0 + zSquared / n;
        double center = probability + zSquared / (2.0 * n);
        double margin = z * Math.sqrt(
                probability * (1.0 - probability) / n
                        + zSquared / (4.0 * n * n)
        );
        return Math.max(0.0, (center - margin) / denominator);
    }

    private double percentile95(List<Double> values) {
        List<Double> sorted = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = Math.max(
                0,
                (int) Math.ceil(0.95 * sorted.size()) - 1
        );
        return sorted.get(index);
    }

    private static void requireUnitInterval(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and in [0, 1]"
            );
        }
    }

    public record ReplayObservation(
            double graphScore,
            double utilityDelta,
            double latencyDeltaMillis,
            boolean safetyViolation
    ) {
        public ReplayObservation {
            requireUnitInterval("graphScore", graphScore);
            if (!Double.isFinite(utilityDelta)) {
                throw new IllegalArgumentException(
                        "utilityDelta must be finite"
                );
            }
            if (!Double.isFinite(latencyDeltaMillis)) {
                throw new IllegalArgumentException(
                        "latencyDeltaMillis must be finite"
                );
            }
        }
    }

    public record CalibrationPolicy(
            int minimumSamples,
            double minimumMeaningfulUtilityDelta,
            double minimumMeanUtilityLift,
            double minimumBenefitProbabilityLowerBound,
            double confidenceZ,
            double maximumRegressionRate,
            double maximumP95LatencyRegressionMillis
    ) {
        public CalibrationPolicy {
            if (minimumSamples < 1) {
                throw new IllegalArgumentException(
                        "minimumSamples must be positive"
                );
            }
            if (!Double.isFinite(minimumMeaningfulUtilityDelta)
                    || minimumMeaningfulUtilityDelta <= 0.0) {
                throw new IllegalArgumentException(
                        "minimumMeaningfulUtilityDelta must be positive"
                );
            }
            if (!Double.isFinite(minimumMeanUtilityLift)) {
                throw new IllegalArgumentException(
                        "minimumMeanUtilityLift must be finite"
                );
            }
            requireUnitInterval(
                    "minimumBenefitProbabilityLowerBound",
                    minimumBenefitProbabilityLowerBound
            );
            if (!Double.isFinite(confidenceZ) || confidenceZ <= 0.0) {
                throw new IllegalArgumentException(
                        "confidenceZ must be positive"
                );
            }
            requireUnitInterval(
                    "maximumRegressionRate",
                    maximumRegressionRate
            );
            if (!Double.isFinite(maximumP95LatencyRegressionMillis)
                    || maximumP95LatencyRegressionMillis < 0.0) {
                throw new IllegalArgumentException(
                        "maximumP95LatencyRegressionMillis "
                                + "must be finite and non-negative"
                );
            }
        }
    }

    public record ThresholdEvaluation(
            double threshold,
            int sampleCount,
            int benefitCount,
            int regressionCount,
            double meanUtilityDelta,
            double benefitProbability,
            double benefitProbabilityLowerBound,
            double regressionRate,
            double p95LatencyDeltaMillis,
            boolean safetyGatePassed,
            boolean qualityGatePassed
    ) {
        public boolean gatePassed() {
            return safetyGatePassed && qualityGatePassed;
        }
    }

    public enum CalibrationDecision {
        INSUFFICIENT_DATA,
        REJECTED,
        REPLAY_CANDIDATE
    }

    public enum GateDecision {
        INSUFFICIENT_DATA,
        REJECTED,
        CANARY_ELIGIBLE
    }

    public record CalibrationReport(
            CalibrationDecision decision,
            ThresholdEvaluation selectedThreshold,
            List<ThresholdEvaluation> evaluations
    ) {
        public CalibrationReport {
            evaluations = evaluations == null
                    ? List.of()
                    : List.copyOf(evaluations);
        }
    }

    public record ReplayGateReport(
            GateDecision decision,
            ThresholdEvaluation evaluation
    ) {
    }
}
