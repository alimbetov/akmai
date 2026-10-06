package kz.alimbetov.akmai.rag.performance;

public enum RagPipelineStage {
    QUERY_ANALYSIS,
    PLANNING,
    RETRIEVAL,
    FUSION,
    RERANK,
    EXPANSION,
    CONTEXT_SELECTION,
    GENERATION,
    CITATION,
    DETERMINISTIC_GROUNDING,
    SEMANTIC_GROUNDING,
    TOTAL
}
