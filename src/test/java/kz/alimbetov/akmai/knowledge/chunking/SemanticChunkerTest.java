package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class SemanticChunkerTest {

    private final SemanticChunker chunker = new SemanticChunker(
            new TextNormalizer(),
            new StructuralUnitExtractor(),
            new DomainSemanticClassifier(),
            new AtomicUnitProtector(),
            new CrossReferenceExtractor(),
            new EmbeddingTextBuilder(),
            new TokenEstimator(),
            new ChunkingProperties(750, 1200, 1800, 100),
            new OversizedUnitSplitter(new TokenEstimator()),
            new ChunkIdentity()
    );

    @Test
    void preservesLegalExceptionWithRule() {
        KnowledgeDocument document = new KnowledgeDocument(
                "law-1",
                "Test law",
                """
                Статья 25. Расторжение договора

                Банк вправе расторгнуть договор при существенном нарушении клиентом условий.
                За исключением случаев, предусмотренных статьёй 48 настоящего договора.
                """,
                "ru",
                KnowledgeDomain.LEGAL,
                Map.of("source", "law.md")
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().rawText())
                .contains("Банк вправе")
                .contains("За исключением");
        assertThat(chunks.getFirst().references())
                .anyMatch(value -> value.toLowerCase().contains("48"));
    }

    @Test
    void createsStableChunkIdsForSameDocumentContent() {
        KnowledgeDocument document = new KnowledgeDocument(
                "stable-1",
                "Stable",
                "Одинаковый текст документа.",
                "ru",
                KnowledgeDomain.GENERAL,
                Map.of("source", "stable.md")
        );

        var first = chunker.chunk(document);
        var second = chunker.chunk(document);

        assertThat(first.getFirst().chunkId()).isEqualTo(second.getFirst().chunkId());
    }

    @Test
    void enrichesEmbeddingTextWithStructure() {
        KnowledgeDocument document = new KnowledgeDocument(
                "med-1",
                "Clinical guide",
                """
                Дозировка

                Начальная доза составляет 10 мг один раз в сутки.
                """,
                "ru",
                KnowledgeDomain.MEDICAL,
                Map.of("source", "clinical.md")
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks.getFirst().embeddingText())
                .contains("Document: Clinical guide")
                .contains("Domain: MEDICAL")
                .contains("Language: ru");
    }
}
