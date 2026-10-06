package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.List;
import java.util.Objects;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;

public final class ContextAssertions {

    private final List<RetrievalHit> actual;
    private final String fixtureId;

    public ContextAssertions(List<RetrievalHit> actual) {
        this(actual, null);
    }

    private ContextAssertions(List<RetrievalHit> actual, String fixtureId) {
        this.actual = actual == null ? List.of() : List.copyOf(actual);
        this.fixtureId = fixtureId;
    }

    public ContextAssertions forFixture(String fixtureId) {
        return new ContextAssertions(actual, fixtureId);
    }

    public ContextAssertions isWithinTokenBudget(
            TokenEstimator tokenEstimator,
            int maxTokens
    ) {
        Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be >= 0");
        }
        int estimated = actual.stream()
                .filter(Objects::nonNull)
                .mapToInt(hit -> tokenEstimator.estimate(hit.text()))
                .sum();
        if (estimated > maxTokens) {
            throw AssuranceFailure.violation(
                    "R-09",
                    fixtureId,
                    "final context must stay within its configured token budget",
                    "context",
                    "estimatedTokens=" + estimated + ", maxTokens=" + maxTokens
            );
        }
        return this;
    }

    public ContextAssertions containsNoBlankEvidence() {
        for (RetrievalHit hit : actual) {
            if (hit == null || hit.text() == null || hit.text().isBlank()) {
                throw AssuranceFailure.violation(
                        "R-09",
                        fixtureId,
                        "final context must contain non-blank evidence",
                        hit == null ? null : hit.chunkId(),
                        "blank context evidence"
                );
            }
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }
}
