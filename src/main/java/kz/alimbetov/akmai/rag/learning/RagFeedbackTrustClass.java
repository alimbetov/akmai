package kz.alimbetov.akmai.rag.learning;

/**
 * Trust is explicit and orthogonal to feedback reason. Public/user feedback is
 * unverified diagnostic evidence and must never directly mutate a production
 * policy. VERIFIED_OPERATOR is reserved for controlled review workflows.
 */
public enum RagFeedbackTrustClass {
    USER_UNVERIFIED(false),
    VERIFIED_OPERATOR(true);

    private final boolean policyEligible;

    RagFeedbackTrustClass(boolean policyEligible) {
        this.policyEligible = policyEligible;
    }

    public boolean policyEligible() {
        return policyEligible;
    }
}
