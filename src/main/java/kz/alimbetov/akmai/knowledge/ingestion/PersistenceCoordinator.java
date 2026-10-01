package kz.alimbetov.akmai.knowledge.ingestion;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class PersistenceCoordinator {

    private final VectorStore vectorStore;
    private final IdentifierSearchIndex identifierSearchIndex;
    private final SearchProjectionFactory projectionFactory;
    private final SearchProjectionRepository projectionRepository;

    public PersistenceCoordinator(
            VectorStore vectorStore,
            IdentifierSearchIndex identifierSearchIndex,
            SearchProjectionFactory projectionFactory,
            SearchProjectionRepository projectionRepository
    ) {
        this.vectorStore = vectorStore;
        this.identifierSearchIndex = identifierSearchIndex;
        this.projectionFactory = projectionFactory;
        this.projectionRepository = projectionRepository;
    }

    public void persist(List<EnrichedKnowledgeChunk> chunks) {
        List<SearchProjection> projections = chunks.stream()
                .map(projectionFactory::create)
                .toList();

        projectionRepository.saveAll(projections);

        vectorStore.add(projections.stream()
                .map(projection -> new Document(
                        projection.embeddingText(),
                        vectorMetadata(projection)
                ))
                .toList());

        List<DocumentIdentifier> identifiers = projections.stream()
                .flatMap(projection -> projection.identifiers().stream()
                        .map(identifier -> new DocumentIdentifier(
                                projection.documentId(),
                                projection.chunkId(),
                                pageNumber(projection),
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

    private int pageNumber(SearchProjection projection) {
        Object page = projection.metadata().get("pageFrom");
        return page instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> vectorMetadata(SearchProjection projection) {
        Map<String, Object> metadata = new HashMap<>(projection.metadata());
        metadata.put("chunkId", projection.chunkId());
        metadata.put("documentId", projection.documentId());
        metadata.put("source", projection.metadata().getOrDefault("source", "unknown"));
        metadata.put("references", String.join(",", projection.references()));
        return metadata;
    }
}
