package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RagPolicyShadowGateService {

    private final RagPolicyRegistryRepository policyRepository;
    private final RagPolicyShadowObservationRepository observationRepository;
    private final ShadowEvaluationProperties properties;

    public RagPolicyShadowGateService(
            RagPolicyRegistryRepository policyRepository,
            RagPolicyShadowObservationRepository observationRepository,
            ShadowEvaluationProperties properties
    ) {
        this.policyRepository = policyRepository;
        this.observationRepository = observationRepository;
        this.properties = properties;
    }

    public GateResult evaluateAndAttach(String policyVersion) {
        RagPolicyRegistryRepository.PolicyRecord policy = policyRepository
                .find(RagPolicyType.RETRIEVAL, policyVersion)
                .orElseThrow(() -> new IllegalArgumentException("Unknown retrieval policy"));
        if (policy.status() != RagPolicyStatus.SHADOW) {
            throw new IllegalStateException(
                    "Shadow evidence can be evaluated only for SHADOW policy"
            );
        }
        Instant since = policy.decidedAt() == null
                ? policy.createdAt()
                : policy.decidedAt();
        RagPolicyShadowObservationRepository.Summary summary =
                observationRepository.summarize(
                        policyVersion,
                        since,
                        properties.maxObservationsPerPolicy()
                );

        boolean passed = summary.observations() >= properties.minSamples()
                && summary.changedPlans() > 0
                && summary.distinctSources() >= properties.minDistinctSources()
                && summary.documentRetention()
                        >= properties.minDocumentEvidenceRecall()
                && summary.chunkRetention()
                        >= properties.minChunkEvidenceRecall()
                && summary.failureRate() <= properties.maxFailureRate()
                && summary.p95LatencyMs() <= properties.maxP95LatencyMs();

        LinkedHashMap<String, Object> quality = new LinkedHashMap<>(
                policy.qualityReport()
        );
        quality.put("shadowPassed", passed);
        quality.put("shadowObservations", summary.observations());
        quality.put("shadowChangedPlans", summary.changedPlans());
        quality.put("shadowDistinctSources", summary.distinctSources());
        quality.put("shadowQueryClasses", summary.queryClasses());
        quality.put("shadowChunkRetention", summary.chunkRetention());
        quality.put("shadowDocumentRetention", summary.documentRetention());
        quality.put("shadowFailureRate", summary.failureRate());
        quality.put("shadowP95LatencyMs", summary.p95LatencyMs());
        quality.put("shadowEvidenceSince", since.toString());
        quality.put("shadowEvidenceEvaluatedAt", Instant.now().toString());

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
            RagPolicyShadowObservationRepository.Summary summary
    ) {
    }
}
