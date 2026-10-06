package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;

public final class ChunkAssertions {

    private final List<KnowledgeChunk> actual;
    private final String fixtureId;

    public ChunkAssertions(List<KnowledgeChunk> actual) {
        this(actual, null);
    }

    private ChunkAssertions(List<KnowledgeChunk> actual, String fixtureId) {
        this.actual = actual == null ? List.of() : List.copyOf(actual);
        this.fixtureId = fixtureId;
    }

    public ChunkAssertions forFixture(String fixtureId) {
        return new ChunkAssertions(actual, fixtureId);
    }

    public ChunkAssertions containsNoEmptyChunks() {
        for (KnowledgeChunk chunk : actual) {
            if (chunk == null) {
                throw AssuranceFailure.violation(
                        "W-03",
                        fixtureId,
                        "chunk must be non-null and contain canonical plus embedding text",
                        null,
                        "null chunk"
                );
            }
            if (isBlank(chunk.normalizedText()) || isBlank(chunk.embeddingText())) {
                throw AssuranceFailure.violation(
                        "W-03",
                        fixtureId,
                        "normalizedText and embeddingText must be non-blank",
                        chunk.chunkId(),
                        "normalizedBlank=" + isBlank(chunk.normalizedText())
                                + ", embeddingBlank=" + isBlank(chunk.embeddingText())
                );
            }
        }
        return this;
    }

    public ChunkAssertions preservesDocumentIdentity(String expectedDocumentId) {
        for (KnowledgeChunk chunk : actual) {
            if (chunk == null || !Objects.equals(expectedDocumentId, chunk.documentId())) {
                throw AssuranceFailure.violation(
                        "W-05",
                        fixtureId,
                        "document identity must survive chunking/enrichment",
                        chunk == null ? null : chunk.chunkId(),
                        "expectedDocumentId=" + expectedDocumentId
                                + ", actualDocumentId="
                                + (chunk == null ? null : chunk.documentId())
                );
            }
        }
        return this;
    }

    public ChunkAssertions preservesMetadata(Map<String, ?> expectedMetadata) {
        Map<String, ?> expected = expectedMetadata == null ? Map.of() : Map.copyOf(expectedMetadata);
        for (KnowledgeChunk chunk : actual) {
            if (chunk == null) {
                throw AssuranceFailure.violation(
                        "W-05",
                        fixtureId,
                        "required metadata must survive chunking/enrichment",
                        null,
                        "null chunk"
                );
            }
            Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
            for (Map.Entry<String, ?> entry : expected.entrySet()) {
                if (!Objects.equals(entry.getValue(), metadata.get(entry.getKey()))) {
                    throw AssuranceFailure.violation(
                            "W-05",
                            fixtureId,
                            "required metadata must survive chunking/enrichment",
                            chunk.chunkId(),
                            "key=" + entry.getKey()
                                    + ", expected=" + entry.getValue()
                                    + ", actual=" + metadata.get(entry.getKey())
                    );
                }
            }
        }
        return this;
    }

    public ChunkAssertions hasConsistentCoreMetadata() {
        for (KnowledgeChunk chunk : actual) {
            if (chunk == null) {
                throw AssuranceFailure.violation(
                        "W-05",
                        fixtureId,
                        "core routing/classification metadata must match the final chunk",
                        null,
                        "null chunk"
                );
            }
            Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
            assertCoreMetadata(chunk, metadata, "documentId", chunk.documentId());
            assertCoreMetadata(chunk, metadata, "chunkIndex", chunk.chunkIndex());
            assertCoreMetadata(chunk, metadata, "language", chunk.language());
            assertCoreMetadata(chunk, metadata, "domain", chunk.domain().name());
            assertCoreMetadata(chunk, metadata, "sectionPath", chunk.sectionPath());
        }
        return this;
    }

    public ChunkAssertions hasUniqueChunkIds() {
        Set<String> seen = new HashSet<>();
        for (KnowledgeChunk chunk : actual) {
            String id = chunk == null ? null : chunk.chunkId();
            if (isBlank(id) || !seen.add(id)) {
                throw AssuranceFailure.violation(
                        "W-02",
                        fixtureId,
                        "chunk ids must be present and unique within a canonical chunk set",
                        id,
                        isBlank(id) ? "blank chunk id" : "duplicate chunk id"
                );
            }
        }
        return this;
    }

    public ChunkAssertions hasChunkIds(Set<String> expectedChunkIds) {
        Set<String> actualIds = actual.stream()
                .filter(Objects::nonNull)
                .map(KnowledgeChunk::chunkId)
                .collect(Collectors.toSet());
        Set<String> expected = expectedChunkIds == null
                ? Set.of()
                : Set.copyOf(expectedChunkIds);
        if (!actualIds.equals(expected)) {
            throw AssuranceFailure.violation(
                    "W-02",
                    fixtureId,
                    "canonical input must preserve deterministic chunk identity",
                    String.join(",", actualIds),
                    "expectedIds=" + expected + ", actualIds=" + actualIds
            );
        }
        return this;
    }

    public ChunkAssertions hasChunkIdsInOrder(List<String> expectedChunkIds) {
        List<String> actualIds = actual.stream()
                .map(chunk -> chunk == null ? null : chunk.chunkId())
                .toList();
        List<String> expected = expectedChunkIds == null
                ? List.of()
                : List.copyOf(expectedChunkIds);
        if (!actualIds.equals(expected)) {
            throw AssuranceFailure.violation(
                    "W-02",
                    fixtureId,
                    "canonical input must preserve deterministic ordered chunk identity",
                    "chunkSequence",
                    "expectedIds=" + expected + ", actualIds=" + actualIds
            );
        }
        return this;
    }

    public ChunkAssertions respectsHardTokenLimit(
            TokenEstimator tokenEstimator,
            int hardMaxTokens
    ) {
        Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        if (hardMaxTokens < 1) {
            throw new IllegalArgumentException("hardMaxTokens must be >= 1");
        }
        for (KnowledgeChunk chunk : actual) {
            if (chunk == null) {
                throw AssuranceFailure.violation(
                        "W-04",
                        fixtureId,
                        "final embedding payload must respect the hard token envelope",
                        null,
                        "null chunk"
                );
            }
            int estimated = tokenEstimator.estimate(chunk.embeddingText());
            if (estimated > hardMaxTokens) {
                throw AssuranceFailure.violation(
                        "W-04",
                        fixtureId,
                        "final embedding payload must respect the hard token envelope",
                        chunk.chunkId(),
                        "estimatedTokens=" + estimated
                                + ", hardMaxTokens=" + hardMaxTokens
                );
            }
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }

    private void assertCoreMetadata(
            KnowledgeChunk chunk,
            Map<String, Object> metadata,
            String key,
            Object expected
    ) {
        Object actualValue = metadata.get(key);
        if (!Objects.equals(expected, actualValue)) {
            throw AssuranceFailure.violation(
                    "W-05",
                    fixtureId,
                    "core routing/classification metadata must match the final chunk",
                    chunk.chunkId(),
                    "key=" + key + ", expected=" + expected + ", actual=" + actualValue
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
