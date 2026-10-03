package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.junit.jupiter.api.Test;

class IndustryAwareChunkingTest {

    private final IndustryProfileRegistry registry =
            new IndustryProfileRegistry(
                    new YamlIndustryProfileLoader().loadAll()
            );

    @Test
    void softwareProfileRecognizesApiEndpointWithoutChangingGeneralFallback() {
        StructuralUnitExtractor extractor = new StructuralUnitExtractor();
        extractor.setProfileRegistry(registry);

        var softwareUnits = extractor.extract(
                document(Map.of("industryCode", "software")),
                "GET /api/customers\n\nReturns all customers."
        );
        var generalUnits = new StructuralUnitExtractor().extract(
                document(Map.of()),
                "GET /api/customers\n\nReturns all customers."
        );

        assertThat(softwareUnits.getFirst().type())
                .isEqualTo(SemanticUnitType.HEADING);
        assertThat(generalUnits.getFirst().type())
                .isEqualTo(SemanticUnitType.PARAGRAPH);
    }

    @Test
    void semanticChunkMetadataRecordsResolvedIndustryProfile() {
        StructuralUnitExtractor extractor = new StructuralUnitExtractor();
        extractor.setProfileRegistry(registry);

        SemanticChunker chunker = new SemanticChunker(
                new TextNormalizer(),
                extractor,
                new DomainSemanticClassifier(),
                new AtomicUnitProtector(),
                new CrossReferenceExtractor(),
                new EmbeddingTextBuilder(),
                new TokenEstimator(),
                new ChunkingProperties(750, 1200, 1800, 1),
                new OversizedUnitSplitter(new TokenEstimator()),
                new ChunkIdentity()
        );
        chunker.setIndustryProfileRegistry(registry);

        var chunks = chunker.chunk(document(
                Map.of("industryCode", "software")
        ));

        assertThat(chunks.getFirst().metadata())
                .containsEntry("industryProfile", "software");
    }

    private KnowledgeDocument document(
            Map<String, Object> metadata
    ) {
        return new KnowledgeDocument(
                "software-doc",
                "Software API",
                "GET /api/customers\n\nReturns all customers.",
                "en",
                KnowledgeDomain.GENERAL,
                metadata
        );
    }
}
