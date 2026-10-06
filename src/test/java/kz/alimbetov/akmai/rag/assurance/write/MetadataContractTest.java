package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

class MetadataContractTest {

    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private final SemanticChunker chunker = new SemanticChunker(
            new TextNormalizer(),
            new StructuralUnitExtractor(),
            new DomainSemanticClassifier(),
            new AtomicUnitProtector(),
            new CrossReferenceExtractor(),
            new EmbeddingTextBuilder(),
            tokenEstimator,
            new ChunkingProperties(80, 120, 180, 10),
            new OversizedUnitSplitter(tokenEstimator),
            new ChunkIdentity()
    );

    @Test
    void finalChunksPreserveRequiredDocumentAndClassificationMetadata() {
        Map<String, Object> requiredMetadata = Map.of(
                "source", "legal-source.md",
                "tenant", "tenant-a",
                "classification", "internal"
        );
        KnowledgeDocument document = new KnowledgeDocument(
                "w05-metadata-001",
                "Metadata contract",
                """
                Article 25

                The agreement remains attributable to the canonical source document.
                The exception in Article 48 remains part of the same legal evidence.
                """,
                "en",
                KnowledgeDomain.LEGAL,
                requiredMetadata
        );

        var chunks = chunker.chunk(document);

        assertFalse(chunks.isEmpty(), "fixture must produce final chunks");
        assertDoesNotThrow(() -> RagAssertions.chunks(chunks)
                .forFixture("w05-required-metadata")
                .preservesDocumentIdentity(document.documentId())
                .preservesMetadata(requiredMetadata)
                .hasConsistentCoreMetadata());
    }
}
