package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class IdentifierRetrievalStrategy implements RetrievalStrategy {

    private final IdentifierSearchIndex searchIndex;

    public IdentifierRetrievalStrategy(IdentifierSearchIndex searchIndex) {
        this.searchIndex = searchIndex;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.IDENTIFIER;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        return queryChunk.identifiers().stream()
                .flatMap(identifier -> searchIndex
                        .search(identifier.normalizedValue(), 10)
                        .stream())
                .map(hit -> new RetrievalHit(
                        RetrievalType.IDENTIFIER,
                        hit.documentId(),
                        hit.chunkId(),
                        hit.contextText(),
                        Map.of(
                                "identifierType", hit.type().name(),
                                "identifier", hit.rawValue(),
                                "pageNumber", hit.pageNumber()
                        )
                ))
                .toList();
    }
}
