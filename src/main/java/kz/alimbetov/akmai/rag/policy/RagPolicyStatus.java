package kz.alimbetov.akmai.rag.policy;

public enum RagPolicyStatus {
    CANDIDATE,
    SHADOW,
    CANARY,
    APPROVED,
    SUPERSEDED,
    REJECTED,
    ROLLED_BACK
}
