package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {

    private final ContextBudget budget = new ContextBudget(new TokenEstimator());

    @Test
    void limitsDominanceBySingleDocument() {
        List<RetrievalHit> hits = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new RetrievalHit(
                        RetrievalType.LEXICAL,
                        "doc-a",
                        "chunk-" + index,
                        "short text",
                        Map.of()
                ))
                .toList();

        assertThat(budget.apply(hits)).hasSize(4);
    }
    @Test
    void rejectsOversizedFirstChunk() {
        String oversized = "word ".repeat(30000);
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.VECTOR,
                "doc-a",
                "oversized",
                oversized,
                Map.of()
        );

        assertThat(budget.apply(List.of(hit))).isEmpty();
    }
}

