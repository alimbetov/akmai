package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;

public final class LifecycleAssertions {

    private final List<RetrievalHit> actual;
    private final String fixtureId;

    public LifecycleAssertions(List<RetrievalHit> actual) {
        this(actual, null);
    }

    private LifecycleAssertions(List<RetrievalHit> actual, String fixtureId) {
        this.actual = actual == null ? List.of() : List.copyOf(actual);
        this.fixtureId = fixtureId;
    }

    public LifecycleAssertions forFixture(String fixtureId) {
        return new LifecycleAssertions(actual, fixtureId);
    }

    /**
     * The publication predicate is supplied by the contract test so this assertion
     * does not duplicate repository/lifecycle production rules.
     */
    public LifecycleAssertions containsPublishedGenerationsOnly(
            Predicate<RetrievalHit> isPublished
    ) {
        Objects.requireNonNull(isPublished, "isPublished");
        for (RetrievalHit hit : actual) {
            if (hit == null || !isPublished.test(hit)) {
                throw AssuranceFailure.violation(
                        "R-03",
                        fixtureId,
                        "final evidence must belong to a readable published generation",
                        hit == null ? null : identity(hit),
                        "publication predicate rejected evidence"
                );
            }
        }
        return this;
    }

    public LifecycleAssertions hasPositiveGenerationIdentity() {
        for (RetrievalHit hit : actual) {
            if (hit == null || hit.generation() < 1) {
                throw AssuranceFailure.violation(
                        "R-03",
                        fixtureId,
                        "readable evidence must retain positive generation identity",
                        hit == null ? null : hit.chunkId(),
                        "generation=" + (hit == null ? null : hit.generation())
                );
            }
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }

    private String identity(RetrievalHit hit) {
        return hit.documentId() + ":" + hit.generation() + ":" + hit.chunkId();
    }
}
