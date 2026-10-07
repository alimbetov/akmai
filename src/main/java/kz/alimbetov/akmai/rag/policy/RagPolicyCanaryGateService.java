package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RagPolicyCanaryGateService {

    private final RagPolicyRegistryRepository policyRepository;
    private final RagPolicyCanaryObservationRepository observationRepository;
    private final CanaryEvaluationProperties properties;

    public RagPolicyCanaryGateService(
            RagPolicyRegistryRepository policyRepository,
            RagPolicyCanaryObservationRepository observationRepository,
            CanaryEvaluationProperties properties
    ) {
        this.policyRepository = policyRepository;
        this.observationRepository = observationRepository;
        this.properties = properties;
    }

    public GateResult evaluateAndAttach(String policyVersion) {
        RagPolicyRegistryRepository.PolicyRecord policy = policyRepository
                .find(RagPolicyType.RETRIEVAL, policyVersion)
                .orElseThrow(() -> new IllegalArgumentException("Unknown retrieval policy"));
        if (policy.status() != RagPolicyStatus.CANARY) {
            throw new IllegalStateException(
                    "Canary evidence can be evaluated only for CANARY policy"
            );
        }
        Instant since = policy.decidedAt() == null
                ? policy.createdAt()
                : policy.decidedAt();
        RagPolicyCanaryObservationRepository.Summary summary =
                observationRepository.summarize(
                        policyVersion,
                        since,
                        properties.maxObservationsPerPolicy()
                );
        RagPolicyCanaryObservationRepository.CohortSummary canary =
                summary.canary();
        RagPolicyCanaryObservationRepository.CohortSummary control =
                summary.control();

        boolean sampleCoverage = canary.samples() >= properties.minCanarySamples()
                && control.samples() >= properties.minControlSamples();
        boolean sourceCoverage = canary.distinctSources()
                        >= properties.minDistinctSources()
                && control.distinctSources()
                        >= properties.minDistinctSources();
        boolean groundedPassed = canary.groundedRate()
                        + properties.maxGroundedRateRegression()
                >= control.groundedRate();
        boolean unavailablePassed = canary.unavailableRate()
                <= control.unavailableRate()
                        + properties.maxUnavailableRateRegression();
        boolean criticalPassed = canary.criticalFailureRate()
                <= properties.maxCriticalFailureRate();
        boolean degradedPassed = canary.degradedRate()
                <= control.degradedRate()
                        + properties.maxDegradedRateRegression();
        boolean latencyPassed = control.p95LatencyMs() > 0
                && canary.p95LatencyMs()
                        <= Math.ceil(
                                control.p95LatencyMs()
                                        * (1.0 + properties.maxP95LatencyRegression())
                        );

        boolean passed = sampleCoverage
                && sourceCoverage
                && groundedPassed
                && unavailablePassed
                && criticalPassed
                && degradedPassed
                && latencyPassed;

        LinkedHashMap<String, Object> quality = new LinkedHashMap<>(
                policy.qualityReport()
        );
        quality.put("canaryPassed", passed);
        quality.put("canarySamples", canary.samples());
        quality.put("canaryControlSamples", control.samples());
        quality.put("canaryDistinctSources", canary.distinctSources());
        quality.put("canaryControlDistinctSources", control.distinctSources());
        quality.put("canaryGroundedRate", canary.groundedRate());
        quality.put("canaryControlGroundedRate", control.groundedRate());
        quality.put("canaryUnavailableRate", canary.unavailableRate());
        quality.put("canaryControlUnavailableRate", control.unavailableRate());
        quality.put("canaryCriticalFailureRate", canary.criticalFailureRate());
        quality.put("canaryControlCriticalFailureRate", control.criticalFailureRate());
        quality.put("canaryDegradedRate", canary.degradedRate());
        quality.put("canaryControlDegradedRate", control.degradedRate());
        quality.put("canaryP95LatencyMs", canary.p95LatencyMs());
        quality.put("canaryControlP95LatencyMs", control.p95LatencyMs());
        quality.put("canaryQueryClasses", canary.queryClasses());
        quality.put("canaryControlQueryClasses", control.queryClasses());
        quality.put("canaryEvidenceSince", since.toString());
        quality.put("canaryEvidenceEvaluatedAt", Instant.now().toString());
        quality.put("canarySampleCoveragePassed", sampleCoverage);
        quality.put("canarySourceCoveragePassed", sourceCoverage);
        quality.put("canaryGroundedRegressionPassed", groundedPassed);
        quality.put("canaryUnavailableRegressionPassed", unavailablePassed);
        quality.put("canaryCriticalFailurePassed", criticalPassed);
        quality.put("canaryDegradedRegressionPassed", degradedPassed);
        quality.put("canaryLatencyRegressionPassed", latencyPassed);

        policyRepository.attachReports(
                RagPolicyType.RETRIEVAL,
                policyVersion,
                Map.copyOf(quality),
                policy.performanceReport()
        );
        return new GateResult(passed, summary);
    }

    public record GateResult(
            boolean passed,
            RagPolicyCanaryObservationRepository.Summary summary
    ) {
    }
}
