package kz.alimbetov.akmai.rag.policy;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RagPolicyPromotionService {

    private final RagPolicyRegistryRepository repository;

    public RagPolicyPromotionService(RagPolicyRegistryRepository repository) {
        this.repository = repository;
    }

    public void makeCanary(RagPolicyType type, String version) {
        RagPolicyRegistryRepository.PolicyRecord policy = repository
                .find(type, version)
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy"));
        requireEvaluationGates(policy);
        repository.markCanary(type, version);
    }

    public void approve(RagPolicyType type, String version) {
        RagPolicyRegistryRepository.PolicyRecord policy = repository
                .find(type, version)
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy"));
        if (policy.status() != RagPolicyStatus.CANARY) {
            throw new IllegalStateException("Only CANARY policy can be approved");
        }
        requireEvaluationGates(policy);
        requireCanaryGate(policy);
        repository.approve(type, version);
    }

    private void requireEvaluationGates(
            RagPolicyRegistryRepository.PolicyRecord policy
    ) {
        Map<String, Object> quality = policy.qualityReport();
        Map<String, Object> performance = policy.performanceReport();
        requireTrue(quality, "securityPassed");
        requireTrue(quality, "correctnessPassed");
        requireTrue(quality, "qualityPassed");
        requireTrue(performance, "performancePassed");
    }

    private void requireCanaryGate(
            RagPolicyRegistryRepository.PolicyRecord policy
    ) {
        Map<String, Object> quality = policy.qualityReport();
        requireTrue(quality, "canaryPassed");
    }

    private void requireTrue(Map<String, Object> report, String key) {
        if (!Boolean.TRUE.equals(report.get(key))) {
            throw new IllegalStateException(
                    "Policy promotion requires gate " + key + "=true"
            );
        }
    }
}
