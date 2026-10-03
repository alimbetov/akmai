package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {

    private final ContextBudget budget = new ContextBudget(new TokenEstimator(), RetrievalTestProperties.defaults());

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
    void capsAdaptiveGraphContextAtTwoChunks() {
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(
                        RetrievalType.LEXICAL,
                        "base-a",
                        "base-a",
                        "base text",
                        Map.of()
                ),
                new RetrievalHit(
                        RetrievalType.VECTOR,
                        "base-b",
                        "base-b",
                        "base text",
                        Map.of()
                ),
                new RetrievalHit(
                        RetrievalType.GRAPH,
                        "graph-a",
                        "graph-a",
                        "graph text",
                        Map.of()
                ),
                new RetrievalHit(
                        RetrievalType.GRAPH,
                        "graph-b",
                        "graph-b",
                        "graph text",
                        Map.of()
                ),
                new RetrievalHit(
                        RetrievalType.GRAPH,
                        "graph-c",
                        "graph-c",
                        "graph text",
                        Map.of()
                )
        );

        List<RetrievalHit> selected = budget.apply(hits);

        assertThat(selected)
                .extracting(RetrievalHit::type)
                .containsExactly(
                        RetrievalType.LEXICAL,
                        RetrievalType.VECTOR,
                        RetrievalType.GRAPH,
                        RetrievalType.GRAPH
                );
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
    @Test
    void budgetsSerializedEnvelopeIncludingLongProvenanceMetadata() {
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc-a",
                "chunk-a",
                "short text",
                Map.of(
                        "source", "very-long-source-".repeat(2_000),
                        "language", "en",
                        "sectionPath", "deep-section-".repeat(2_000),
                        "pageFrom", 7,
                        "pageTo", 8
                )
        );

        assertThat(budget.apply(List.of(hit))).isEmpty();
    }

}
