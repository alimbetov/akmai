package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class EmbeddingContractTest {

    private static final int HARD_MAX_TOKENS = 90;

    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private final SemanticChunker chunker = new SemanticChunker(
            new TextNormalizer(),
            new StructuralUnitExtractor(),
            new DomainSemanticClassifier(),
            new AtomicUnitProtector(),
            new CrossReferenceExtractor(),
            new EmbeddingTextBuilder(),
            tokenEstimator,
            new ChunkingProperties(30, 50, HARD_MAX_TOKENS, 5),
            new OversizedUnitSplitter(tokenEstimator),
            new ChunkIdentity()
    );

    @Test
    void finalEnrichedEmbeddingPayloadNeverExceedsHardEnvelope() {
        String oversizedEvidence = ("Evidence remains attributable and bounded after enrichment. ")
                .repeat(120);
        KnowledgeDocument document = new KnowledgeDocument(
                "w04-envelope-001",
                "Embedding envelope",
                "Section\n\n" + oversizedEvidence,
                "en",
                KnowledgeDomain.GENERAL,
                Map.of("source", "w04-envelope-001.md")
        );

        var chunks = chunker.chunk(document);

        assertTrue(chunks.size() > 1, "fixture must force the oversized unit to split");
        assertDoesNotThrow(() -> RagAssertions.chunks(chunks)
                .forFixture("w04-final-embedding-envelope")
                .containsNoEmptyChunks()
                .respectsHardTokenLimit(tokenEstimator, HARD_MAX_TOKENS));
    }
}
