package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;

/**
 * Adapter boundary for a real ColBERT/late-interaction model runtime.
 * Implementations must return one finite score per candidate in input order.
 */
public interface ColbertRerankClient {

    List<Double> score(String question, List<RetrievalHit> candidates);
}
