package kz.alimbetov.akmai.knowledge.ingestion;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class PersistenceCoordinator {

    private final VectorStore vectorStore;
    private final IdentifierSearchIndex identifierSearchIndex;

    public PersistenceCoordinator(
            VectorStore vectorStore,
            IdentifierSearchIndex identifierSearchIndex
    ) {
        this.vectorStore = vectorStore;
        this.identifierSearchIndex = identifierSearchIndex;
    }

    public void persist(List<EnrichedKnowledgeChunk> chunks) {
        vectorStore.add(chunks.stream()
                .map(EnrichedKnowledgeChunk::chunk)
                .map(chunk -> new Document(chunk.embeddingText(), vectorMetadata(chunk)))
                .toList());

        List<DocumentIdentifier> identifiers = chunks.stream()
                .flatMap(enriched -> enriched.identifiers().stream()
                        .map(identifier -> new DocumentIdentifier(
                                enriched.chunk().documentId(),
                                enriched.chunk().chunkId(),
                                pageNumber(enriched.chunk()),
                                identifier.type(),
                                identifier.rawValue(),
                                identifier.normalizedValue(),
                                identifier.contextText(),
                                Instant.now()
                        )))
                .toList();

        if (!identifiers.isEmpty()) {
            identifierSearchIndex.index(identifiers);
        }
    }

    private int pageNumber(KnowledgeChunk chunk) {
        Object page = chunk.metadata().get("pageFrom");
        return page instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> vectorMetadata(KnowledgeChunk chunk) {
        Map<String, Object> metadata = new HashMap<>(chunk.metadata());
        metadata.put("chunkId", chunk.chunkId());
        metadata.put("source", chunk.metadata().getOrDefault("source", "unknown"));
        metadata.put("references", String.join(",", chunk.references()));
        return metadata;
    }
}
