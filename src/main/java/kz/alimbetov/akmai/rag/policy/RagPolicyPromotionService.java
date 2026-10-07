package kz.alimbetov.akmai.rag.policy;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RagPolicyPromotionService {

    private final RagPolicyRegistryRepository repository;
    private final ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider;
    private final ShadowRetrievalPolicyProvider shadowRetrievalPolicyProvider;
    private final CanaryRetrievalPolicyProvider canaryRetrievalPolicyProvider;
    private final RagPolicyShadowGateService shadowGateService;
    private final RagPolicyCanaryGateService canaryGateService;

    public RagPolicyPromotionService(RagPolicyRegistryRepository repository) {
        this(repository, null, null, null, null, null);
    }

    public RagPolicyPromotionService(
            RagPolicyRegistryRepository repository,
            ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider,
            ShadowRetrievalPolicyProvider shadowRetrievalPolicyProvider
    ) {
        this(
                repository,
                approvedRetrievalPolicyProvider,
                shadowRetrievalPolicyProvider,
                null,
                null,
                null
        );
    }

    public RagPolicyPromotionService(
            RagPolicyRegistryRepository repository,
            ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider,
            ShadowRetrievalPolicyProvider shadowRetrievalPolicyProvider,
            RagPolicyShadowGateService shadowGateService
    ) {
        this(
                repository,
                approvedRetrievalPolicyProvider,
                shadowRetrievalPolicyProvider,
                null,
                shadowGateService,
                null
        );
    }

    @Autowired
    public RagPolicyPromotionService(
            RagPolicyRegistryRepository repository,
            ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider,
            ShadowRetrievalPolicyProvider shadowRetrievalPolicyProvider,
            CanaryRetrievalPolicyProvider canaryRetrievalPolicyProvider,
            RagPolicyShadowGateService shadowGateService,
            RagPolicyCanaryGateService canaryGateService
    ) {
        this.repository = repository;
        this.approvedRetrievalPolicyProvider = approvedRetrievalPolicyProvider;
        this.shadowRetrievalPolicyProvider = shadowRetrievalPolicyProvider;
        this.canaryRetrievalPolicyProvider = canaryRetrievalPolicyProvider;
        this.shadowGateService = shadowGateService;
        this.canaryGateService = canaryGateService;
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
        if (type == RagPolicyType.RETRIEVAL && shadowGateService != null) {
            shadowGateService.evaluateAndAttach(version);
            policy = policy(type, version);
        }
        requireShadowGate(policy);
        repository.markCanary(type, version);
        invalidateShadow(type);
        invalidateCanary(type);
    }

    public void approve(RagPolicyType type, String version) {
        RagPolicyRegistryRepository.PolicyRecord policy = policy(type, version);
        if (policy.status() != RagPolicyStatus.CANARY) {
            throw new IllegalStateException("Only CANARY policy can be approved");
        }
        requireEvaluationGates(policy);
        requireShadowGate(policy);
        if (type == RagPolicyType.RETRIEVAL && canaryGateService != null) {
            canaryGateService.evaluateAndAttach(version);
            policy = policy(type, version);
        }
        requireCanaryGate(policy);
        repository.approve(type, version);
        invalidateApproved(type);
        invalidateCanary(type);
    }

    public void reject(RagPolicyType type, String version) {
        RagPolicyRegistryRepository.PolicyRecord policy = policy(type, version);
        if (policy.status() != RagPolicyStatus.CANDIDATE
                && policy.status() != RagPolicyStatus.SHADOW
                && policy.status() != RagPolicyStatus.CANARY) {
            throw new IllegalStateException(
                    "Only CANDIDATE, SHADOW or CANARY policy can be rejected"
            );
        }
        repository.reject(type, version);
        invalidateShadow(type);
        invalidateCanary(type);
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

    private void invalidateCanary(RagPolicyType type) {
        if (type == RagPolicyType.RETRIEVAL
                && canaryRetrievalPolicyProvider != null) {
            canaryRetrievalPolicyProvider.invalidate();
        }
    }
}
