package kz.alimbetov.akmai.knowledge.ingestion;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;

public record EnrichedKnowledgeChunk(
        KnowledgeChunk chunk,
        List<DetectedIdentifier> identifiers,
        List<String> references
) {
}
