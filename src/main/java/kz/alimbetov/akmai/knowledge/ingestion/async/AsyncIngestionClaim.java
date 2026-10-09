package kz.alimbetov.akmai.knowledge.ingestion.async;

import java.util.UUID;

public record AsyncIngestionClaim(
        UUID ingestionId,
        String leaseOwner,
        long leaseVersion,
        AsyncIngestionJob job
) {
}
