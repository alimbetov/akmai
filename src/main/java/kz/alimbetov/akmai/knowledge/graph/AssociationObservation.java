package kz.alimbetov.akmai.knowledge.graph;

public record AssociationObservation(
        ChunkGraphNode left,
        ChunkGraphNode right,
        AssociationBand band,
        AssociationEvidence evidence
) {
}
