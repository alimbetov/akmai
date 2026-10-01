package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;

public interface SemanticRerankScorer {

    List<Double> score(String question, List<RetrievalHit> hits);
}
