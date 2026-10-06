package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;

public final class RetrievalAssertions {

    private final List<RetrievalHit> actual;
    private final String fixtureId;

    public RetrievalAssertions(List<RetrievalHit> actual) {
        this(actual, null);
    }

    private RetrievalAssertions(List<RetrievalHit> actual, String fixtureId) {
        this.actual = actual == null ? List.of() : List.copyOf(actual);
        this.fixtureId = fixtureId;
    }

    public RetrievalAssertions forFixture(String fixtureId) {
        return new RetrievalAssertions(actual, fixtureId);
    }

    public RetrievalAssertions hasNoDuplicateCanonicalHits() {
        Set<String> identities = new HashSet<>();
        for (RetrievalHit hit : actual) {
            if (hit == null) {
                throw AssuranceFailure.violation(
                        "R-04",
                        fixtureId,
                        "fused retrieval output must contain canonical hits only once",
                        null,
                        "null retrieval hit"
                );
            }
            String identity = canonicalIdentity(hit);
            if (!identities.add(identity)) {
                throw AssuranceFailure.violation(
                        "R-04",
                        fixtureId,
                        "fused retrieval output must contain canonical hits only once",
                        identity,
                        "duplicate canonical hit"
                );
            }
        }
        return this;
    }

    public RetrievalAssertions hasRetrievalEvidence() {
        for (RetrievalHit hit : actual) {
            if (hit == null || hit.evidence() == null || hit.evidence().isEmpty()) {
                throw AssuranceFailure.violation(
                        "R-04",
                        fixtureId,
                        "fused retrieval hits must preserve contributing retrieval evidence",
                        hit == null ? null : canonicalIdentity(hit),
                        "retrieval evidence is empty"
                );
            }
        }
        return this;
    }

    public RetrievalAssertions hasRoutingIdentity() {
        for (RetrievalHit hit : actual) {
            if (hit == null || !hit.hasRoutingIdentity()) {
                throw AssuranceFailure.violation(
                        "R-03",
                        fixtureId,
                        "retrieval evidence must retain access/generation/document/chunk routing identity",
                        hit == null ? null : hit.chunkId(),
                        "routing identity is incomplete"
                );
            }
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }

    private String canonicalIdentity(RetrievalHit hit) {
        return hit.documentId() + ":" + hit.generation() + ":" + hit.chunkId();
    }
}
