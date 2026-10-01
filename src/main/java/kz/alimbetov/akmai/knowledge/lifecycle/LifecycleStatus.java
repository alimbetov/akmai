package kz.alimbetov.akmai.knowledge.lifecycle;

public enum LifecycleStatus {
    INGESTING,
    READY,
    DELETE_PENDING,
    DELETING,
    DELETE_FAILED,
    DELETED
}
