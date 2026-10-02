package kz.alimbetov.akmai.rag.retrieval;

import java.util.HashMap;
import java.util.List;
import kz.alimbetov.akmai.knowledge.vector.PublishedVectorSearchRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class VectorRetrievalStrategy implements RetrievalStrategy {

    private final PublishedVectorSearchRepository repository;
    private final RetrievalProperties properties;

    public VectorRetrievalStrategy(
            PublishedVectorSearchRepository repository,
            RetrievalProperties properties
    ) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.VECTOR;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        return repository.search(
                        queryChunk.semanticText(),
                        List.copyOf(context.documentIds()),
                        context.accessLevels(),
                        properties.vectorTopK(),
                        properties.vectorSimilarityThreshold()
                ).stream()
                .map(match -> {
                    HashMap<String, Object> metadata =
                            new HashMap<>(match.metadata());
                    metadata.put("score", match.score());
                    metadata.put("generation", match.generation());
                    return new RetrievalHit(
                            RetrievalType.VECTOR,
                            match.documentId(),
                            match.chunkId(),
                            match.content(),
                            metadata
                    );
                })
                .toList();
    }
}
