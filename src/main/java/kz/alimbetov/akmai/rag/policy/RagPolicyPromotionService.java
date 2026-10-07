package kz.alimbetov.akmai.rag.policy;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RagPolicyPromotionService {

    private final RagPolicyRegistryRepository repository;
    private final ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider;
    private final ShadowRetrievalPolicyProvider shadowRetrievalPolicyProvider;

    public RagPolicyPromotionService(RagPolicyRegistryRepository repository) {
        this(repository, null, null);
    }

    @Autowired
    public RagPolicyPromotionService(
            RagPolicyRegistryRepository repository,
            ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider,
            ShadowRetrievalPolicyProvider shadowRetrievalPolicyProvider
    ) {
        this.repository = repository;
        this.approvedRetrievalPolicyProvider = approvedRetrievalPolicyProvider;
        this.shadowRetrievalPolicyProvider = shadowRetrievalPolicyProvider;
    }

    public void makeShadow(RagPolicyType type, String version) {
        RagPolicyRegistryRepository.PolicyRecord policy = policy(type, version);
        if (policy.status() != RagPolicyStatus.CANDIDATE) {
            throw new IllegalStateException("Only CANDIDATE policy can enter SHADOW");
        }
        requireEvaluationGates(policy);
        repository.markShadow(type, version);
        invalidateShadow(type);
    }

    public void makeCanary(RagPolicyType type, String version) {
        RagPolicyRegistryRepository.PolicyRecord policy = policy(type, version);
        if (policy.status() != RagPolicyStatus.SHADOW) {
            throw new IllegalStateException("Only SHADOW policy can enter CANARY");
        }
        requireEvaluationGates(policy);
        requireShadowGate(policy);
        repository.markCanary(type, version);
        invalidateShadow(type);
    }

    public void approve(RagPolicyType type, String version) {
        RagPolicyRegistryRepository.PolicyRecord policy = policy(type, version);
        if (policy.status() != RagPolicyStatus.CANARY) {
            throw new IllegalStateException("Only CANARY policy can be approved");
        }
        requireEvaluationGates(policy);
        requireShadowGate(policy);
        requireCanaryGate(policy);
        repository.approve(type, version);
        invalidateApproved(type);
    }

    public void rollback(RagPolicyType type, String targetVersion) {
        RagPolicyRegistryRepository.PolicyRecord target = policy(type, targetVersion);
        if (target.status() != RagPolicyStatus.SUPERSEDED) {
            throw new IllegalStateException(
                    "Rollback target must be a previously approved SUPERSEDED policy"
            );
        }
        repository.rollbackTo(type, targetVersion);
        invalidateApproved(type);
    }

    private RagPolicyRegistryRepository.PolicyRecord policy(
            RagPolicyType type,
            String version
    ) {
        return repository.find(type, version)
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy"));
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

    private void requireShadowGate(
            RagPolicyRegistryRepository.PolicyRecord policy
    ) {
        requireTrue(policy.qualityReport(), "shadowPassed");
    }

    private void requireCanaryGate(
            RagPolicyRegistryRepository.PolicyRecord policy
    ) {
        requireTrue(policy.qualityReport(), "canaryPassed");
    }

    private void requireTrue(Map<String, Object> report, String key) {
        if (!Boolean.TRUE.equals(report.get(key))) {
            throw new IllegalStateException(
                    "Policy promotion requires gate " + key + "=true"
            );
        }
    }

    private void invalidateApproved(RagPolicyType type) {
        if (type == RagPolicyType.RETRIEVAL
                && approvedRetrievalPolicyProvider != null) {
            approvedRetrievalPolicyProvider.invalidate();
        }
    }

    private void invalidateShadow(RagPolicyType type) {
        if (type == RagPolicyType.RETRIEVAL
                && shadowRetrievalPolicyProvider != null) {
            shadowRetrievalPolicyProvider.invalidate();
        }
    }
}
