package kz.alimbetov.akmai.knowledge.graph;

import java.time.Instant;

public record AssociationEvidence(
        double weight,
        long supportDelta,
        long contextDelta,
        long citationDelta,
        long distinctQueryDelta,
        Instant observedAt,
        int graphVersion
) {

    public AssociationEvidence {
        if (!Double.isFinite(weight) || weight < 0 || weight > 1) {
            throw new IllegalArgumentException("weight must be in [0, 1]");
        }
        if (supportDelta < 0
                || contextDelta < 0
                || citationDelta < 0
                || distinctQueryDelta < 0) {
            throw new IllegalArgumentException(
                    "association evidence deltas must not be negative"
            );
        }
        if (supportDelta == 0
                && contextDelta == 0
                && citationDelta == 0
                && distinctQueryDelta == 0) {
            throw new IllegalArgumentException(
                    "association evidence must contain a positive signal"
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
