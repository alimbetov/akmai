package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import org.junit.jupiter.api.Test;

class ReferenceContractTest {

    private final CrossReferenceExtractor extractor = new CrossReferenceExtractor();
    private final SearchProjectionFactory projectionFactory = new SearchProjectionFactory();

    @Test
    void crossReferenceRetainsDocumentAndChunkOwnershipThroughProjection() {
        KnowledgeChunk chunk = chunk(
                "w08-chunk-001",
                "w08-document-001",
                "Условия применяются за исключением случаев, предусмотренных статьёй 48."
        );
        var references = extractor.extract(chunk.rawText(), chunk.language());

        assertFalse(references.isEmpty(), "fixture must contain a detected cross-reference");
        String articleReference = references.stream()
                .filter(value -> value.contains("48"))
                .findFirst()
                .orElseThrow();
        var projection = projectionFactory.create(new EnrichedKnowledgeChunk(
                chunk,
                List.of(),
                references
        ));

        assertDoesNotThrow(() -> RagAssertions.projections(List.of(projection))
                .forFixture("w08-article-reference-authority")
                .preservesReferenceAuthority(
                        articleReference,
                        chunk.documentId(),
                        chunk.chunkId()
                ));
    }

    private KnowledgeChunk chunk(String chunkId, String documentId, String text) {
        return new KnowledgeChunk(
                chunkId,
                documentId,
                null,
                0,
                text,
                text,
                text,
                "Reference authority",
                "Статья 25",
                "ru",
                KnowledgeDomain.LEGAL,
                List.of(),
                Map.of("source", "reference-authority.md")
        );
    }
}
