package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import kz.alimbetov.akmai.rag.query.QueryChunk;

public interface RetrievalStrategy {

    RetrievalType type();

    boolean supports(QueryChunk queryChunk);

    List<RetrievalHit> retrieve(QueryChunk queryChunk);
}
