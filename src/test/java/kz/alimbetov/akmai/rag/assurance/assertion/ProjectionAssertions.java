package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;

public final class ProjectionAssertions {

    private final List<SearchProjection> actual;
    private final String fixtureId;

    public ProjectionAssertions(List<SearchProjection> actual) {
        this(actual, null);
    }

    private ProjectionAssertions(List<SearchProjection> actual, String fixtureId) {
        this.actual = actual == null ? List.of() : List.copyOf(actual);
        this.fixtureId = fixtureId;
    }

    public ProjectionAssertions forFixture(String fixtureId) {
        return new ProjectionAssertions(actual, fixtureId);
    }

    public ProjectionAssertions preservesIdentifierAuthority(
            String normalizedValue,
            String expectedDocumentId,
            String expectedChunkId
    ) {
        List<SearchProjection> owners = actual.stream()
                .filter(Objects::nonNull)
                .filter(projection -> projection.identifiers().stream()
                        .anyMatch(identifier -> Objects.equals(
                                normalizedValue,
                                identifier.normalizedValue()
                        )))
                .toList();
        if (owners.isEmpty()) {
            throw AssuranceFailure.violation(
                    "W-07",
                    fixtureId,
                    "canonical identifier must survive enrichment and projection",
                    normalizedValue,
                    "identifier not present in projected evidence"
            );
        }
        for (SearchProjection owner : owners) {
            if (!Objects.equals(expectedDocumentId, owner.documentId())
                    || !Objects.equals(expectedChunkId, owner.chunkId())) {
                throw AssuranceFailure.violation(
                        "W-07",
                        fixtureId,
                        "canonical identifier must retain its owning document and chunk",
                        normalizedValue,
                        "expectedOwner=" + expectedDocumentId + ":" + expectedChunkId
                                + ", actualOwner=" + owner.documentId() + ":" + owner.chunkId()
                );
            }
        }
        return this;
    }

    public ProjectionAssertions preservesReferenceAuthority(
            String rawReference,
            String expectedDocumentId,
            String expectedChunkId
    ) {
        List<SearchProjection> owners = actual.stream()
                .filter(Objects::nonNull)
                .filter(projection -> projection.references().stream()
                        .anyMatch(reference -> Objects.equals(rawReference, reference)))
                .toList();
        if (owners.isEmpty()) {
            throw AssuranceFailure.violation(
                    "W-08",
                    fixtureId,
                    "cross-reference must survive enrichment and projection",
                    rawReference,
                    "reference not present in projected evidence"
            );
        }
        for (SearchProjection owner : owners) {
            if (!Objects.equals(expectedDocumentId, owner.documentId())
                    || !Objects.equals(expectedChunkId, owner.chunkId())) {
                throw AssuranceFailure.violation(
                        "W-08",
                        fixtureId,
                        "cross-reference must retain its owning document and chunk",
                        rawReference,
                        "expectedOwner=" + expectedDocumentId + ":" + expectedChunkId
                                + ", actualOwner=" + owner.documentId() + ":" + owner.chunkId()
                );
            }
        }
        return this;
    }

    public ProjectionAssertions containsPublishedGenerationOnly(long expectedGeneration) {
        for (SearchProjection projection : actual) {
            if (projection == null || projection.generation() != expectedGeneration) {
                throw AssuranceFailure.violation(
                        "W-09",
                        fixtureId,
                        "normal read paths must expose only the published generation",
                        projection == null ? null : projection.chunkId(),
                        "expectedGeneration=" + expectedGeneration
                                + ", actualGeneration="
                                + (projection == null ? null : projection.generation())
                );
            }
        }
        return this;
    }

    public ProjectionAssertions hasChunkIds(Set<String> expectedChunkIds) {
        Set<String> expected = expectedChunkIds == null
                ? Set.of()
                : Set.copyOf(expectedChunkIds);
        Set<String> actualIds = actual.stream()
                .filter(Objects::nonNull)
                .map(SearchProjection::chunkId)
                .collect(Collectors.toSet());
        if (!expected.equals(actualIds)) {
            throw AssuranceFailure.violation(
                    "W-09",
                    fixtureId,
                    "published projection set must match readable evidence",
                    "projectionSet",
                    "expectedChunkIds=" + expected + ", actualChunkIds=" + actualIds
            );
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }
}
