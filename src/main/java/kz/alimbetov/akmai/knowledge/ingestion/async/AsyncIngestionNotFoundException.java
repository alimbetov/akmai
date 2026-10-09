package kz.alimbetov.akmai.knowledge.ingestion.async;

import java.util.UUID;

public class AsyncIngestionNotFoundException extends RuntimeException {

    public AsyncIngestionNotFoundException(UUID ingestionId) {
        super("Async ingestion job not found: " + ingestionId);
    }
}
