package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.junit.jupiter.api.Test;

class ChunkingAcceptanceRegressionTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void canonicalUnicodeFormsProduceSameTextAndChunkIdentity() {
        TextNormalizer normalizer = new TextNormalizer();
        ChunkIdentity identity = new ChunkIdentity();
        String nfc = "Café";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);

        assertThat(normalizer.normalize(nfd)).isEqualTo(nfc);
        assertThat(identity.create("doc", 1, "section", nfd))
                .isEqualTo(identity.create("doc", 1, "section", nfc));
    }

    @Test
    void oversizedSplitterNeverCreatesUnpairedSurrogate() {
        OversizedUnitSplitter splitter = new OversizedUnitSplitter(estimator);
        String text = "A".repeat(31) + "😀" + "B".repeat(400);
        SemanticUnit unit = new SemanticUnit(
                text,
                "section",
                SemanticUnitType.PARAGRAPH,
                false
        );

        var parts = splitter.split(unit, 20);

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts)
                .allSatisfy(part -> assertThat(hasUnpairedSurrogate(part.text()))
                        .isFalse());
        assertThat(parts.stream().map(SemanticUnit::text).reduce("", String::concat))
                .isEqualTo(text);
    }

    @Test
    void legalNegationWinsOverPositiveDeonticPrefix() {
        DomainSemanticClassifier classifier = new DomainSemanticClassifier();

        assertThat(classifier.classify(
                "The bank must not disclose the data.",
                KnowledgeDomain.LEGAL
        )).isEqualTo(SemanticUnitType.PROHIBITION);
        assertThat(classifier.classify(
                "The bank shall not disclose the data.",
                KnowledgeDomain.LEGAL
        )).isEqualTo(SemanticUnitType.PROHIBITION);
        assertThat(classifier.classify(
                "The bank may not disclose the data.",
                KnowledgeDomain.LEGAL
        )).isEqualTo(SemanticUnitType.PROHIBITION);
        assertThat(classifier.classify(
                "The bank must disclose the data.",
                KnowledgeDomain.LEGAL
        )).isEqualTo(SemanticUnitType.OBLIGATION);
        assertThat(classifier.classify(
                "The mayonnaise recipe is attached.",
                KnowledgeDomain.LEGAL
        )).isEqualTo(SemanticUnitType.PARAGRAPH);
    }

    @Test
    void numberedMedicalFactsRemainIndependentSemanticChunks() {
        SemanticChunker chunker = chunker(new ChunkingProperties(40, 60, 80, 1));
        KnowledgeDocument document = document(
                "med-numbered",
                "en",
                KnowledgeDomain.MEDICAL,
                """
                1. Dosage: take 10 mg once daily.
                2. Contraindication: do not use in severe renal failure.
                3. Monitoring: monitor blood pressure weekly.
                """
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks)
                .filteredOn(chunk -> chunk.rawText().contains("Dosage:"))
                .hasSize(1);
        assertThat(chunks)
                .filteredOn(chunk -> chunk.rawText().contains("Contraindication:"))
                .hasSize(1);
        assertThat(chunks)
                .filteredOn(chunk -> chunk.rawText().contains("Monitoring:"))
                .hasSize(1);
    }

    @Test
    void chineseMedicalFactsSplitWithoutWhitespaceAndRemainAtomic() {
        SemanticChunker chunker = chunker(new ChunkingProperties(40, 60, 80, 1));
        KnowledgeDocument document = document(
                "med-zh-atomic",
                "zh",
                KnowledgeDomain.MEDICAL,
                "剂量: 每日一次10毫克。禁忌: 严重肾功能衰竭属于禁忌。监测: 每周监测血压。"
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks)
                .filteredOn(chunk -> chunk.rawText().contains("剂量:"))
                .hasSize(1);
        assertThat(chunks)
                .filteredOn(chunk -> chunk.rawText().contains("禁忌:"))
                .hasSize(1);
        assertThat(chunks)
                .filteredOn(chunk -> chunk.rawText().contains("监测:"))
                .hasSize(1);
    }

    @Test
    void finalChunkAndEmbeddingPayloadNeverExceedHardMaximum() {
        ChunkingProperties properties = new ChunkingProperties(35, 45, 55, 1);
        SemanticChunker chunker = chunker(properties);
        KnowledgeDocument document = new KnowledgeDocument(
                "hard-bound",
                "Compact title",
                "Short intro.\n\n" + "Long semantic sentence ".repeat(120),
                "en",
                KnowledgeDomain.GENERAL,
                Map.of("source", "hard-bound")
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(
                        estimator.estimate(chunk.embeddingText())
                ).isLessThanOrEqualTo(properties.hardMaxTokens()));
    }

    private SemanticChunker chunker(ChunkingProperties properties) {
        return new SemanticChunker(
                new TextNormalizer(),
                new StructuralUnitExtractor(),
                new DomainSemanticClassifier(),
                new AtomicUnitProtector(),
                new CrossReferenceExtractor(),
                new EmbeddingTextBuilder(),
                estimator,
                properties,
                new OversizedUnitSplitter(estimator),
                new ChunkIdentity()
        );
    }

    private KnowledgeDocument document(
            String id,
            String language,
            KnowledgeDomain domain,
            String text
    ) {
        return new KnowledgeDocument(
                id,
                id,
                text,
                language,
                domain,
                Map.of("source", id)
        );
    }

    private boolean hasUnpairedSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(value)) {
                return true;
            }
        }
        return false;
    }
}
