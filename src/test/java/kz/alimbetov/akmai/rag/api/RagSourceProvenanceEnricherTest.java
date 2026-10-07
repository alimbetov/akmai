package kz.alimbetov.akmai.rag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.junit.jupiter.api.Test;

class RagSourceProvenanceEnricherTest {

    @Test
    void exposesCanonicalBlockAndBoundingBoxFromPublishedAclEligibleProjection() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        RagSourceProvenanceEnricher subject = new RagSourceProvenanceEnricher(reader);
        Set<Long> scope = Set.of(7L);
        SearchProjection projection = projection(Map.of(
                "canonicalBlocks",
                List.of(Map.of(
                        "blockId", "b-17",
                        "pageFrom", 7,
                        "pageTo", 7,
                        "sectionPath", "Chapter 2 > Section 4",
                        "boundingBox", Map.of(
                                "x", 10.5,
                                "y", 20.25,
                                "width", 100.0,
                                "height", 30.5
                        )
                ))
        ));
        when(reader.findByDocumentAndChunkIds(
                "doc-1",
                List.of("chunk-1"),
                scope
        )).thenReturn(List.of(projection));
        RagResponse response = new RagResponse(
                "request-1",
                "Answer [1]",
                List.of(new RagResponse.Source(
                        1,
                        "doc-1",
                        "chunk-1",
                        "s3://bucket/doc-1.pdf",
                        "en",
                        "Chapter 2 > Section 4",
                        "7"
                ))
        );

        RagResponse enriched = subject.enrich(response, scope);

        assertThat(enriched.requestId()).isEqualTo("request-1");
        assertThat(enriched.answer()).isEqualTo("Answer [1]");
        assertThat(enriched.sources()).singleElement().satisfies(source -> {
            assertThat(source.provenance().canonicalBlocks())
                    .singleElement()
                    .satisfies(block -> {
                        assertThat(block.blockId()).isEqualTo("b-17");
                        assertThat(block.pageFrom()).isEqualTo(7);
                        assertThat(block.pageTo()).isEqualTo(7);
                        assertThat(block.sectionPath())
                                .isEqualTo("Chapter 2 > Section 4");
                        assertThat(block.boundingBox()).isEqualTo(
                                new RagResponse.BoundingBox(
                                        10.5,
                                        20.25,
                                        100.0,
                                        30.5
                                )
                        );
                    });
        });
        verify(reader).findByDocumentAndChunkIds(
                "doc-1",
                List.of("chunk-1"),
                scope
        );
    }

    @Test
    void fallsBackToLegacyBlockMetadataWithoutInventingBoundingBox() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        RagSourceProvenanceEnricher subject = new RagSourceProvenanceEnricher(reader);
        Set<Long> scope = Set.of(1L);
        when(reader.findByDocumentAndChunkIds(
                "doc-1",
                List.of("chunk-1"),
                scope
        )).thenReturn(List.of(projection(Map.of(
                "blockIds", List.of("legacy-1", "legacy-2"),
                "pageFrom", 3,
                "pageTo", 4,
                "sectionPath", "Legacy section"
        ))));
        RagResponse response = response();

        RagResponse enriched = subject.enrich(response, scope);

        assertThat(enriched.sources().getFirst().provenance().canonicalBlocks())
                .extracting(RagResponse.CanonicalBlock::blockId)
                .containsExactly("legacy-1", "legacy-2");
        assertThat(enriched.sources().getFirst().provenance().canonicalBlocks())
                .allSatisfy(block -> {
                    assertThat(block.pageFrom()).isEqualTo(3);
                    assertThat(block.pageTo()).isEqualTo(4);
                    assertThat(block.sectionPath()).isEqualTo("Legacy section");
                    assertThat(block.boundingBox()).isNull();
                });
    }

    @Test
    void missingAclEligibleProjectionLeavesExistingResponseUntouched() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        RagSourceProvenanceEnricher subject = new RagSourceProvenanceEnricher(reader);
        Set<Long> scope = Set.of(1L);
        when(reader.findByDocumentAndChunkIds(
                "doc-1",
                List.of("chunk-1"),
                scope
        )).thenReturn(List.of());
        RagResponse response = response();

        RagResponse enriched = subject.enrich(response, scope);

        assertThat(enriched.sources().getFirst().provenance().canonicalBlocks())
                .isEmpty();
    }

    private RagResponse response() {
        return new RagResponse(
                "request-1",
                "Answer [1]",
                List.of(new RagResponse.Source(
                        1,
                        "doc-1",
                        "chunk-1",
                        "source",
                        "en",
                        "Section",
                        "3-4"
                ))
        );
    }

    private SearchProjection projection(Map<String, Object> metadata) {
        return new SearchProjection(
                "chunk-1",
                "doc-1",
                null,
                0,
                "text",
                "embedding",
                "en",
                KnowledgeDomain.TECHNICAL,
                "Section",
                List.of(),
                List.of(),
                metadata,
                2
        );
    }
}
