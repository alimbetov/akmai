package kz.alimbetov.akmai.knowledge.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.chunking.SemanticChunker;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIngestionService {

    private final SemanticChunker semanticChunker;
    private final VectorStore vectorStore;

    public KnowledgeIngestionService(
            SemanticChunker semanticChunker,
            VectorStore vectorStore
    ) {
        this.semanticChunker = semanticChunker;
        this.vectorStore = vectorStore;
    }

    public KnowledgeIngestionResponse addText(AddKnowledgeRequest request) {
        Map<String, Object> metadata = new HashMap<>(
                request.metadata() == null ? Map.of() : request.metadata()
        );
        metadata.put("source", request.source());

        KnowledgeDocument document = new KnowledgeDocument(
                request.documentId(),
                request.title(),
                request.text(),
                request.language(),
                request.domain(),
                metadata
        );

        List<KnowledgeChunk> chunks = semanticChunker.chunk(document);

        List<Document> vectorDocuments = chunks.stream()
                .map(chunk -> new Document(
                        chunk.embeddingText(),
                        toVectorMetadata(chunk)
                ))
                .toList();

        vectorStore.add(vectorDocuments);

        return new KnowledgeIngestionResponse(
                document.documentId(),
                chunks.size()
        );
    }

    private Map<String, Object> toVectorMetadata(KnowledgeChunk chunk) {
        Map<String, Object> metadata = new HashMap<>(chunk.metadata());
        metadata.put("chunkId", chunk.chunkId());
        metadata.put("source", chunk.metadata().getOrDefault("source", "unknown"));
        metadata.put("references", String.join(",", chunk.references()));
        return metadata;
    }
}
