package kz.alimbetov.akmai.rag.retrieval;

public interface SemanticRerankScorer {

    double score(String question, RetrievalHit hit);
}
