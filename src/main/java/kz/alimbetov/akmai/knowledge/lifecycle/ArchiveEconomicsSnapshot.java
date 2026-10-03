package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;

public record ArchiveEconomicsSnapshot(
        Instant capturedAt,
        long pendingGenerations,
        long pendingChunks,
        long oldestRetiredAgeSeconds,
        StoreFootprint projection,
        StoreFootprint vector
) {
    public ArchiveEconomicsSnapshot {
        if (capturedAt == null) {
            throw new IllegalArgumentException("capturedAt must not be null");
        }
        if (projection == null || vector == null) {
            throw new IllegalArgumentException(
                    "archive store footprints must not be null"
            );
        }
    }

    public record StoreFootprint(
            long estimatedLiveRows,
            long estimatedDeadRows,
            long insertedRows,
            long deletedRows,
            long autovacuumRuns,
            long totalBytes,
            long leafCount,
            long maxLeafBytes
    ) {
        public static StoreFootprint empty() {
            return new StoreFootprint(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
