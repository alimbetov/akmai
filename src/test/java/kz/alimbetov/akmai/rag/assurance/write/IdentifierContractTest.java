package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.BusinessIdentifierParsers;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import org.junit.jupiter.api.Test;

class IdentifierContractTest {

    private final IdentifierNormalizer normalizer = new IdentifierNormalizer();
    private final IdentifierExtractor extractor = new IdentifierExtractor(List.of(
            new BusinessIdentifierParsers.ContractNumberParser(normalizer),
            new BusinessIdentifierParsers.OrderNumberParser(normalizer),
            new BusinessIdentifierParsers.InvoiceNumberParser(normalizer),
            new BusinessIdentifierParsers.ApplicationNumberParser(normalizer),
            new BusinessIdentifierParsers.CaseNumberParser(normalizer),
            new BusinessIdentifierParsers.DocumentNumberParser(normalizer)
    ));
    private final SearchProjectionFactory projectionFactory = new SearchProjectionFactory();

    @Test
    void canonicalIdentifierRetainsDocumentAndChunkOwnershipThroughProjection() {
        KnowledgeChunk chunk = chunk(
                "w07-chunk-001",
                "w07-document-001",
                "Договор № KZ-2026-001847 регулирует условия обслуживания."
        );
        var identifiers = extractor.extract(chunk.rawText());

        assertFalse(identifiers.isEmpty(), "fixture must contain a detected identifier");
        var projection = projectionFactory.create(new EnrichedKnowledgeChunk(
                chunk,
                identifiers,
                List.of()
        ));

        assertDoesNotThrow(() -> RagAssertions.projections(List.of(projection))
                .forFixture("w07-contract-number-authority")
                .preservesIdentifierAuthority(
                        "KZ-2026-001847",
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
                "Identifier authority",
                "Section",
                "ru",
                KnowledgeDomain.LEGAL,
                List.of(),
                Map.of("source", "identifier-authority.md")
        );
    }
}
