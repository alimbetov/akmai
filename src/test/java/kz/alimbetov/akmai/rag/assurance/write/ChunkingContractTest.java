package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.chunking.AtomicUnitProtector;
import kz.alimbetov.akmai.knowledge.chunking.ChunkIdentity;
import kz.alimbetov.akmai.knowledge.chunking.ChunkingProperties;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.chunking.DomainSemanticClassifier;
import kz.alimbetov.akmai.knowledge.chunking.EmbeddingTextBuilder;
import kz.alimbetov.akmai.knowledge.chunking.OversizedUnitSplitter;
import kz.alimbetov.akmai.knowledge.chunking.SemanticChunker;
import kz.alimbetov.akmai.knowledge.chunking.StructuralUnitExtractor;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import org.junit.jupiter.api.Test;

class ChunkingContractTest {

    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private final SemanticChunker chunker = new SemanticChunker(
            new TextNormalizer(),
            new StructuralUnitExtractor(),
            new DomainSemanticClassifier(),
            new AtomicUnitProtector(),
            new CrossReferenceExtractor(),
            new EmbeddingTextBuilder(),
            tokenEstimator,
            new ChunkingProperties(30, 45, 100, 5),
            new OversizedUnitSplitter(tokenEstimator),
            new ChunkIdentity()
    );

    @Test
    void sameCanonicalDocumentProducesSameOrderedChunkIdentity() {
        KnowledgeDocument document = document();

        var first = chunker.chunk(document);
        var second = chunker.chunk(document);
        List<String> firstIds = first.stream().map(chunk -> chunk.chunkId()).toList();

        assertFalse(first.isEmpty(), "fixture must exercise at least one final chunk");
        assertDoesNotThrow(() -> RagAssertions.chunks(first)
                .forFixture("w02-stable-chunk-sequence")
                .hasUniqueChunkIds()
                .hasChunkIdsInOrder(firstIds)
                .containsNoEmptyChunks());
        assertDoesNotThrow(() -> RagAssertions.chunks(second)
                .forFixture("w02-stable-chunk-sequence-repeat")
                .hasUniqueChunkIds()
                .hasChunkIdsInOrder(firstIds)
                .containsNoEmptyChunks());
    }

    @Test
    void finalChunksAlwaysContainCanonicalAndEmbeddingText() {
        var chunks = chunker.chunk(document());

        assertFalse(chunks.isEmpty(), "fixture must exercise at least one final chunk");
        assertDoesNotThrow(() -> RagAssertions.chunks(chunks)
                .forFixture("w03-non-empty-final-chunks")
                .containsNoEmptyChunks());
    }

    private KnowledgeDocument document() {
        return new KnowledgeDocument(
                "contract-write-001",
                "Contract write fixture",
                """
                Section One

                Alpha evidence is stable and attributable to the same source document.
                It contains enough content to exercise a semantic chunk boundary safely.

                Section Two

                Beta evidence remains deterministic across repeated chunking executions.
                It is intentionally separate from the first section for boundary coverage.

                Section Three

                Gamma evidence verifies that final chunks never carry blank canonical text.
                The embedding payload must also remain non-blank after enrichment.
                """,
                "en",
                KnowledgeDomain.GENERAL,
                Map.of("source", "contract-write-001.md", "tenant", "test")
        );
    }
}
