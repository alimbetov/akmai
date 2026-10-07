package kz.alimbetov.akmai.rag.policy;

public enum RagPolicyStatus {
    CANDIDATE,
    SHADOW,
    CANARY,
    APPROVED,
    REJECTED,
    ROLLED_BACK
}
