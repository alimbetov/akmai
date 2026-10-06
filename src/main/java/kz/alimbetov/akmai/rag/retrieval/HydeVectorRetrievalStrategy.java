package kz.alimbetov.akmai.rag.retrieval;

import java.util.HashMap;
import java.util.List;
import kz.alimbetov.akmai.knowledge.vector.PublishedVectorSearchRepository;
import kz.alimbetov.akmai.rag.query.HydeQueryGenerator;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class HydeVectorRetrievalStrategy implements RetrievalStrategy {

    private final HydeQueryGenerator hydeQueryGenerator;
    private final PublishedVectorSearchRepository repository;
    private final RetrievalProperties properties;

    public HydeVectorRetrievalStrategy(
            HydeQueryGenerator hydeQueryGenerator,
            PublishedVectorSearchRepository repository,
            RetrievalProperties properties
    ) {
        this.hydeQueryGenerator = hydeQueryGenerator;
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.HYDE_VECTOR;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        String hypothetical = hydeQueryGenerator.generate(
                queryChunk.rawText(),
                context.accessLevels()
        );
        if (hypothetical.isBlank()) {
            return List.of();
        }

        return repository.search(
                        hypothetical,
                        queryChunk.language(),
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
                    metadata.put("queryMode", "HYDE");
                    return new RetrievalHit(
                            RetrievalType.HYDE_VECTOR,
                            match.accessLevel(),
                            match.documentId(),
                            match.generation(),
                            match.chunkId(),
                            match.content(),
                            metadata
                    );
                })
                .toList();
    }
}
