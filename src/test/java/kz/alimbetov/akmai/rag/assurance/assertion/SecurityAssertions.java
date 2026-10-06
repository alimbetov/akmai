package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;

public final class SecurityAssertions {

    private final List<RetrievalHit> actual;
    private final String fixtureId;

    public SecurityAssertions(List<RetrievalHit> actual) {
        this(actual, null);
    }

    private SecurityAssertions(List<RetrievalHit> actual, String fixtureId) {
        this.actual = actual == null ? List.of() : List.copyOf(actual);
        this.fixtureId = fixtureId;
    }

    public SecurityAssertions forFixture(String fixtureId) {
        return new SecurityAssertions(actual, fixtureId);
    }

    public SecurityAssertions hasNoAclLeak(Set<Long> allowedAccessLevels) {
        Set<Long> allowed = allowedAccessLevels == null
                ? Set.of()
                : Set.copyOf(allowedAccessLevels);
        for (RetrievalHit hit : actual) {
            if (hit == null || !allowed.contains(hit.accessLevel())) {
                throw AssuranceFailure.violation(
                        "R-02",
                        fixtureId,
                        "retrieved/fused/expanded/final evidence must remain inside caller ACL",
                        hit == null ? null : hit.chunkId(),
                        "accessLevel=" + (hit == null ? null : hit.accessLevel())
                                + ", allowed=" + allowed
                );
            }
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }
}
