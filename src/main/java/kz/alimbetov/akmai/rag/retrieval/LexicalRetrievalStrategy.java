package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class LexicalRetrievalStrategy implements RetrievalStrategy {

    @Override
    public RetrievalType type() {
        return RetrievalType.LEXICAL;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        // Projection contract is intentionally present before the concrete local
        // lexical index. This keeps RetrievalPlanner stable when Lucene/FTS is added.
        return List.of();
    }
}
