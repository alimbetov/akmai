package kz.alimbetov.akmai.knowledge.ingestion.async;

public enum AsyncIngestionJobStatus {
    ACCEPTED,
    PROCESSING,
    RETRY_WAIT,
    INGESTED,
    FAILED
}
