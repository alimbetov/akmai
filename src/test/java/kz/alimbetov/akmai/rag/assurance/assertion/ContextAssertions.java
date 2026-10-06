package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.rag.retrieval.ContextAssembler;
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

    public ContextAssertions isWithinSerializedTokenBudget(
            TokenEstimator tokenEstimator,
            ContextAssembler contextAssembler,
            int maxTokens
    ) {
        Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        Objects.requireNonNull(contextAssembler, "contextAssembler");
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be >= 0");
        }
        String serialized = contextAssembler.assemble(actual);
        int estimated = tokenEstimator.estimate(serialized);
        if (estimated > maxTokens) {
            throw AssuranceFailure.violation(
                    "R-09",
                    fixtureId,
                    "serialized final context including formatting overhead must stay within budget",
                    "context",
                    "serializedTokens=" + estimated + ", maxTokens=" + maxTokens
            );
        }
        return this;
    }

    public ContextAssertions excludesTemporalEvidence(String... forbiddenChunkIds) {
        Set<String> forbidden = ids(forbiddenChunkIds);
        for (RetrievalHit hit : actual) {
            if (hit != null && forbidden.contains(hit.chunkId())) {
                throw AssuranceFailure.violation(
                        "R-08",
                        fixtureId,
                        "expired, superseded, withdrawn or otherwise inactive evidence must not enter final context",
                        hit.chunkId(),
                        "forbiddenTemporalEvidence=" + forbidden
                );
            }
        }
        return this;
    }

    public ContextAssertions containsOnlyRevalidatedChunkIds(String... expectedChunkIds) {
        Set<String> expected = ids(expectedChunkIds);
        Set<String> observed = actual.stream()
                .filter(Objects::nonNull)
                .map(RetrievalHit::chunkId)
                .collect(Collectors.toSet());
        if (!observed.equals(expected)) {
            throw AssuranceFailure.violation(
                    "R-10",
                    fixtureId,
                    "final revalidation must remove evidence that is no longer readable",
                    "context",
                    "expectedChunkIds=" + expected + ", actualChunkIds=" + observed
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

    private Set<String> ids(String... values) {
        return values == null
                ? Set.of()
                : Arrays.stream(values)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
    }
}
