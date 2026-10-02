package kz.alimbetov.akmai.knowledge.projection;

import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import org.springframework.stereotype.Component;

@Component
public class SearchProjectionFactory {

    public SearchProjection create(EnrichedKnowledgeChunk enriched) {
        var chunk = enriched.chunk();
        return new SearchProjection(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.parentChunkId(),
                chunk.chunkIndex(),
                chunk.rawText(),
                chunk.embeddingText(),
                chunk.language(),
                chunk.domain(),
                chunk.sectionPath(),
                enriched.identifiers(),
                enriched.references(),
                chunk.metadata(),
                2
        );
    }

    public SearchProjection create(EnrichedKnowledgeChunk enriched, long generation) {
        return create(enriched).withGeneration(generation);
    }
}
