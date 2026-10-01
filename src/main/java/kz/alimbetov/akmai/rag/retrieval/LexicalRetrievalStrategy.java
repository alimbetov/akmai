package kz.alimbetov.akmai.rag.retrieval;

import java.util.HashMap;
import java.util.List;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class LexicalRetrievalStrategy implements RetrievalStrategy {

    private final SearchProjectionRepository repository;
    private final RetrievalProperties properties;

    public LexicalRetrievalStrategy(SearchProjectionRepository repository, RetrievalProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.LEXICAL;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        return repository.searchLexical(
                        queryChunk.semanticText(),
                        List.copyOf(context.documentIds()),
                        properties.lexicalLimit()
                ).stream()
                .map(projection -> new RetrievalHit(
                        RetrievalType.LEXICAL,
                        projection.documentId(),
                        projection.chunkId(),
                        projection.text(),
                        new HashMap<>(projection.metadata())
                ))
                .toList();
    }
}
