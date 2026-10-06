package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;

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

    public RetrievalAssertions hasEvidenceTypes(
            String chunkId,
            RetrievalType... expectedTypes
    ) {
        RetrievalHit hit = findByChunkId(chunkId, "R-04");
        Set<RetrievalType> expected = expectedTypes == null
                ? Set.of()
                : Arrays.stream(expectedTypes)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Set<RetrievalType> actualTypes = hit.evidence().stream()
                .map(evidence -> evidence.type())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!actualTypes.containsAll(expected)) {
            throw AssuranceFailure.violation(
                    "R-04",
                    fixtureId,
                    "canonical fused hit must preserve all contributing retrieval evidence",
                    chunkId,
                    "expectedEvidence=" + expected + ", actualEvidence=" + actualTypes
            );
        }
        return this;
    }

    public RetrievalAssertions hasAuthorityTier(String chunkId, int expectedTier) {
        RetrievalHit hit = findByChunkId(chunkId, "R-05");
        Object value = hit.metadata().get("authorityTier");
        int actualTier = value instanceof Number number
                ? number.intValue()
                : Integer.MAX_VALUE;
        if (actualTier != expectedTier) {
            throw AssuranceFailure.violation(
                    "R-05",
                    fixtureId,
                    "exact identifier/reference authority must survive fusion and reranking boundaries",
                    chunkId,
                    "expectedAuthorityTier=" + expectedTier
                            + ", actualAuthorityTier=" + value
            );
        }
        return this;
    }

    public RetrievalAssertions hasSameCanonicalSequenceAs(List<RetrievalHit> expectedHits) {
        List<String> expected = canonicalSequence(expectedHits);
        List<String> observed = canonicalSequence(actual);
        if (!observed.equals(expected)) {
            throw AssuranceFailure.violation(
                    "R-06",
                    fixtureId,
                    "reranker fallback must preserve the candidate set and order",
                    "retrievalSequence",
                    "expected=" + expected + ", actual=" + observed
            );
        }
        return this;
    }

    public RetrievalAssertions containsChunkIds(String... expectedChunkIds) {
        Set<String> expected = expectedChunkIds == null
                ? Set.of()
                : Arrays.stream(expectedChunkIds)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Set<String> ids = actual.stream()
                .filter(Objects::nonNull)
                .map(RetrievalHit::chunkId)
                .collect(Collectors.toSet());
        if (!ids.containsAll(expected)) {
            throw AssuranceFailure.violation(
                    "R-04",
                    fixtureId,
                    "retrieval output must contain expected canonical evidence",
                    "retrievalSet",
                    "expected=" + expected + ", actual=" + ids
            );
        }
        return this;
    }

    public RetrievalAssertions excludesChunkIds(String... forbiddenChunkIds) {
        Set<String> forbidden = forbiddenChunkIds == null
                ? Set.of()
                : Arrays.stream(forbiddenChunkIds)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        for (RetrievalHit hit : actual) {
            if (hit != null && forbidden.contains(hit.chunkId())) {
                throw AssuranceFailure.violation(
                        "R-03",
                        fixtureId,
                        "stale or ineligible retrieval evidence must not survive canonicalization",
                        hit.chunkId(),
                        "forbiddenChunkIds=" + forbidden
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

    private RetrievalHit findByChunkId(String chunkId, String contractId) {
        return actual.stream()
                .filter(Objects::nonNull)
                .filter(hit -> Objects.equals(chunkId, hit.chunkId()))
                .findFirst()
                .orElseThrow(() -> AssuranceFailure.violation(
                        contractId,
                        fixtureId,
                        "expected canonical retrieval hit must exist",
                        chunkId,
                        "availableChunkIds=" + actual.stream()
                                .filter(Objects::nonNull)
                                .map(RetrievalHit::chunkId)
                                .toList()
                ));
    }

    private List<String> canonicalSequence(List<RetrievalHit> hits) {
        if (hits == null) {
            return List.of();
        }
        return hits.stream()
                .map(hit -> hit == null ? "<null>" : canonicalIdentity(hit))
                .toList();
    }

    private String canonicalIdentity(RetrievalHit hit) {
        return hit.documentId() + ":" + hit.generation() + ":" + hit.chunkId();
    }
}
