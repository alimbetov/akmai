package kz.alimbetov.akmai.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
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
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalDocumentChunkingTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final SemanticChunker chunker = new SemanticChunker(
            new TextNormalizer(),
            new StructuralUnitExtractor(),
            new DomainSemanticClassifier(),
            new AtomicUnitProtector(),
            new CrossReferenceExtractor(),
            new EmbeddingTextBuilder(),
            estimator,
            new ChunkingProperties(750, 1200, 1800, 100),
            new OversizedUnitSplitter(estimator),
            new ChunkIdentity()
    );
    private final CanonicalDocumentMapper mapper = new CanonicalDocumentMapper();

    @Test
    void blockAndPageProvenanceSurvivesSemanticGrouping() {
        CanonicalDocument source = new CanonicalDocument(
                "contract-1",
                "7",
                "Договор",
                "s3://files/contract-1/v7.pdf",
                "ru",
                KnowledgeDomain.LEGAL,
                7L,
                List.of(
                        new CanonicalDocument.Block(
                                "b-heading",
                                CanonicalDocument.BlockType.HEADING,
                                "Статья 25. Расторжение договора",
                                2,
                                7,
                                7,
                                "Раздел II > Статья 25",
                                null
                        ),
                        new CanonicalDocument.Block(
                                "b-rule",
                                CanonicalDocument.BlockType.PARAGRAPH,
                                "Банк вправе расторгнуть договор при просрочке более 30 календарных дней.",
                                null,
                                7,
                                7,
                                "Раздел II > Статья 25",
                                null
                        ),
                        new CanonicalDocument.Block(
                                "b-exception",
                                CanonicalDocument.BlockType.PARAGRAPH,
                                "Это правило не применяется, если просрочка возникла вследствие ошибки банка.",
                                null,
                                8,
                                8,
                                "Раздел II > Статья 25",
                                null
                        )
                ),
                Map.of("fileId", "file-123")
        );

        var prepared = mapper.prepare(source);
        var chunks = chunker.chunk(
                prepared.document(),
                prepared.semanticUnits()
        );

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().metadata().get("blockIds"))
                .isEqualTo(List.of("b-heading", "b-rule", "b-exception"));
        assertThat(chunks.getFirst().metadata().get("pageFrom")).isEqualTo(7);
        assertThat(chunks.getFirst().metadata().get("pageTo")).isEqualTo(8);
        assertThat(chunks.getFirst().metadata().get("canonicalVersion"))
                .isEqualTo("7");
        assertThat(chunks.getFirst().sectionPath())
                .isEqualTo("Раздел II > Статья 25");
    }
}
