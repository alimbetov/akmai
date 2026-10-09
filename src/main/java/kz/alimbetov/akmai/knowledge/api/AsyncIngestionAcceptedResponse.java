package kz.alimbetov.akmai.knowledge.api;

import java.util.UUID;

public record AsyncIngestionAcceptedResponse(
        int schemaVersion,
        UUID ingestionId,
        String documentId,
        String status
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
