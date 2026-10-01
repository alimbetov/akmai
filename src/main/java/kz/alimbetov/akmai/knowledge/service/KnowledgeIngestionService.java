package kz.alimbetov.akmai.knowledge.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.ArrayList;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.chunking.SemanticChunker;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIngestionService {

    private final SemanticChunker semanticChunker;
    private final VectorStore vectorStore;
    private final IdentifierExtractor identifierExtractor;
    private final IdentifierSearchIndex identifierSearchIndex;

    public KnowledgeIngestionService(
            SemanticChunker semanticChunker,
            VectorStore vectorStore,
            IdentifierExtractor identifierExtractor,
            IdentifierSearchIndex identifierSearchIndex
    ) {
        this.semanticChunker = semanticChunker;
        this.vectorStore = vectorStore;
        this.identifierExtractor = identifierExtractor;
        this.identifierSearchIndex = identifierSearchIndex;
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

        List<DocumentIdentifier> identifiers = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            identifierExtractor.extract(chunk.rawText()).forEach(detected ->
                    identifiers.add(new DocumentIdentifier(
                            chunk.documentId(),
                            chunk.chunkId(),
                            chunk.location().pageFrom(),
                            detected.type(),
                            detected.rawValue(),
                            detected.normalizedValue(),
                            detected.contextText(),
                            Instant.now()
                    ))
            );
        }
        identifierSearchIndex.index(identifiers);

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
