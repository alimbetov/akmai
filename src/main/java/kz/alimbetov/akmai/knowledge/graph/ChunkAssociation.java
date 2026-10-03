package kz.alimbetov.akmai.knowledge.graph;

import java.time.Instant;

public record ChunkAssociation(
        ChunkGraphNode source,
        ChunkGraphNode target,
        AssociationBand band,
        double weight,
        long supportCount,
        long contextCount,
        long citationCount,
        long distinctQuerySupport,
        Instant lastReinforcedAt,
        int graphVersion
) {
}
