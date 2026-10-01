package kz.alimbetov.akmai.rag.retrieval;
import java.util.List;
import org.springframework.stereotype.Component;
@Component
public class Reranker {
    public List<RetrievalHit> rerank(List<RetrievalHit> hits, String question) {
        return hits;
    }
}
