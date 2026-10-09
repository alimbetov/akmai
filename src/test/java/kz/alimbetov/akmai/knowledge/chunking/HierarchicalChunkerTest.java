package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class HierarchicalChunkerTest {

    @Test
    void splitsNormalParentIntoTwoBoundedChildren() {
        SemanticChunker semanticChunker = mock(SemanticChunker.class);
        TokenEstimator estimator = new TokenEstimator();
        EmbeddingTextBuilder embeddingTextBuilder = new EmbeddingTextBuilder();
        TextNormalizer normalizer = new TextNormalizer();
        CrossReferenceExtractor referenceExtractor =
                mock(CrossReferenceExtractor.class);
        when(referenceExtractor.extract(anyString(), anyString()))
                .thenReturn(List.of());

        ParentChildProperties properties = new ParentChildProperties(
                true,
                250,
                275,
                300,
                true,
                8
        );
        HierarchicalChunker chunker = new HierarchicalChunker(
                semanticChunker,
                properties,
                estimator,
                new OversizedUnitSplitter(estimator),
                embeddingTextBuilder,
                normalizer,
                referenceExtractor,
                new ChunkIdentity()
        );

        KnowledgeDocument document = new KnowledgeDocument(
                "doc-1",
                "Parent child test",
                "unused",
                "en",
                KnowledgeDomain.GENERAL,
                Map.of()
        );
        String section = "Section 1";
        String rawText = parentTextAround(
                550,
                document,
                section,
                estimator,
                embeddingTextBuilder
        );
        KnowledgeChunk parent = new KnowledgeChunk(
                "parent-1",
                document.documentId(),
                null,
                0,
                rawText,
                normalizer.normalize(rawText),
                embeddingTextBuilder.build(document, section, rawText),
                document.title(),
                section,
                document.language(),
                document.domain(),
                List.of(),
                Map.of("chunkIndex", 0)
        );
        when(semanticChunker.chunk(document)).thenReturn(List.of(parent));

        List<KnowledgeChunk> hierarchy = chunker.chunk(document);
        List<KnowledgeChunk> children = hierarchy.stream()
                .filter(value -> ChunkRole.fromMetadata(value.metadata())
                        == ChunkRole.CHILD)
                .toList();

        assertThat(hierarchy).hasSize(3);
        assertThat(hierarchy)
                .extracting(KnowledgeChunk::chunkIndex)
                .containsExactly(0, 1, 2)
                .doesNotHaveDuplicates();
        assertThat(ChunkRole.fromMetadata(hierarchy.getFirst().metadata()))
                .isEqualTo(ChunkRole.PARENT);
        assertThat(children).hasSize(2);
        assertThat(children)
                .allSatisfy(child -> {
                    assertThat(child.parentChunkId()).isEqualTo("parent-1");
                    assertThat(child.chunkId()).startsWith("cc1_");
                    assertThat(estimator.estimate(child.embeddingText()))
                            .isLessThanOrEqualTo(300);
                    assertThat(ChunkRole.fromMetadata(child.metadata()))
                            .isEqualTo(ChunkRole.CHILD);
                    assertThat(child.metadata().get("chunkIndex"))
                            .isEqualTo(child.chunkIndex());
                });
        assertThat(children)
                .extracting(child -> child.metadata().get(
                        ChunkRole.CHILD_COUNT_KEY
                ))
                .containsOnly(2);
    }

    private String parentTextAround(
            int targetTokens,
            KnowledgeDocument document,
            String section,
            TokenEstimator estimator,
            EmbeddingTextBuilder embeddingTextBuilder
    ) {
        String sentence = "The supplier shall deliver the goods on time and report any delay immediately. ";
        StringBuilder text = new StringBuilder();
        while (estimator.estimate(embeddingTextBuilder.build(
                document,
                section,
                text.toString()
        )) < targetTokens) {
            text.append(sentence);
        }
        return text.toString().trim();
    }
}
