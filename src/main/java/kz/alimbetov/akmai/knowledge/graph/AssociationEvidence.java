package kz.alimbetov.akmai.knowledge.graph;

import java.time.Instant;

public record AssociationEvidence(
        double weight,
        long supportDelta,
        long contextDelta,
        long citationDelta,
        int querySupportBucket,
        Instant observedAt,
        int graphVersion
) {

    public static final int QUERY_SUPPORT_BUCKETS = 256;

    public AssociationEvidence {
        if (!Double.isFinite(weight) || weight < 0 || weight > 1) {
            throw new IllegalArgumentException("weight must be in [0, 1]");
        }
        if (supportDelta < 0
                || contextDelta < 0
                || citationDelta < 0) {
            throw new IllegalArgumentException(
                    "association evidence deltas must not be negative"
            );
        }
        if (supportDelta == 0
                && contextDelta == 0
                && citationDelta == 0) {
            throw new IllegalArgumentException(
                    "association evidence must contain a positive signal"
            );
        }
        if (querySupportBucket < 0
                || querySupportBucket >= QUERY_SUPPORT_BUCKETS) {
            throw new IllegalArgumentException(
                    "querySupportBucket must be in [0, 255]"
            );
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }
        if (graphVersion <= 0) {
            throw new IllegalArgumentException(
                    "graphVersion must be positive"
            );
        }
    }
}
