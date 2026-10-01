package kz.alimbetov.akmai.rag.retrieval.plan;

import java.util.List;

public record RetrievalPlan(List<RetrievalStep> steps) {
}
