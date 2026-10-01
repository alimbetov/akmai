package kz.alimbetov.akmai.knowledge.lifecycle;

public enum LifecycleStatus {
    INGESTING,
    INGEST_FAILED,
    READY,
    DELETE_PENDING,
    DELETING,
    DELETE_FAILED,
    DELETED
}
