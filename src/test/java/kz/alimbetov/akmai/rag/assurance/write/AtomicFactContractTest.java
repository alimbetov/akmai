package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

class AtomicFactContractTest {

    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private final SemanticChunker chunker = new SemanticChunker(
            new TextNormalizer(),
            new StructuralUnitExtractor(),
            new DomainSemanticClassifier(),
            new AtomicUnitProtector(),
            new CrossReferenceExtractor(),
            new EmbeddingTextBuilder(),
            tokenEstimator,
            new ChunkingProperties(750, 1200, 1800, 1),
            new OversizedUnitSplitter(tokenEstimator),
            new ChunkIdentity()
    );

    @Test
    void legalRuleAndExceptionRemainJointlyRecoverable() {
        KnowledgeDocument document = new KnowledgeDocument(
                "w06-legal-ru-001",
                "Правило и исключение",
                """
                Статья 25. Расторжение договора

                Банк вправе расторгнуть договор при существенном нарушении условий.
                За исключением случаев, предусмотренных статьёй 48 настоящего договора.
                """,
                "ru",
                KnowledgeDomain.LEGAL,
                Map.of("source", "w06-legal-ru-001.md")
        );

        var chunks = chunker.chunk(document);

        assertDoesNotThrow(() -> RagAssertions.chunks(chunks)
                .forFixture("w06-legal-rule-exception")
                .preservesAtomicFact(
                        "Банк вправе расторгнуть договор",
                        "За исключением случаев"
                ));
    }

    @Test
    void medicalDrugDoseRouteAndFrequencyRemainJointlyRecoverable() {
        KnowledgeDocument document = new KnowledgeDocument(
                "w06-medical-en-001",
                "Clinical guidance",
                """
                Dosage

                Dosage: amlodipine 10 mg orally once daily for hypertension.
                """,
                "en",
                KnowledgeDomain.MEDICAL,
                Map.of("source", "w06-medical-en-001.md")
        );

        var chunks = chunker.chunk(document);

        assertDoesNotThrow(() -> RagAssertions.chunks(chunks)
                .forFixture("w06-medical-drug-dose-route-frequency")
                .preservesAtomicFact("amlodipine", "10 mg", "orally", "once daily"));
    }
}
