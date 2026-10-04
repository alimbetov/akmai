package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class SemanticChunkAnnotatorTest {

    private final SemanticDomainCatalog catalog =
            new SemanticDomainCatalog();
    private final SemanticChunkAnnotator annotator =
            new SemanticChunkAnnotator(
                    new SemanticDomainRouter(catalog)
            );

    @Test
    void annotatesChunkWithBoundedSemanticDomainMetadata() {
        KnowledgeChunk annotated = annotator.annotate(chunk(
                "Machine learning algorithm performance is evaluated.",
                "en"
        ));

        assertThat(annotated.metadata())
                .containsEntry(
                        "semanticOntologyVersion",
                        "semantic-domain-v2"
                );
        assertThat(annotated.metadata().get("semanticDomains"))
                .asList()
                .contains("computer_science_ai");
        assertThat(annotated.metadata().get("semanticDomainScores"))
                .isInstanceOf(Map.class);
    }

    @Test
    void preservesExistingMetadataAndLeavesUnknownTextUntouched() {
        KnowledgeChunk original = chunk(
                "Please summarize this document.",
                "en"
        );

        KnowledgeChunk annotated = annotator.annotate(original);

        assertThat(annotated).isSameAs(original);
        assertThat(annotated.metadata())
                .containsEntry("source", "source.md");
    }

    @Test
    void keepsAtMostThreeSoftDomains() {
        KnowledgeChunk annotated = annotator.annotate(chunk(
                "Bank credit climate geology algorithm machine learning "
                        + "diagnosis treatment manufacturing factory",
                "en"
        ));

        assertThat(annotated.metadata().get("semanticDomains"))
                .asList()
                .hasSizeLessThanOrEqualTo(3);
    }

    private KnowledgeChunk chunk(String text, String language) {
        return new KnowledgeChunk(
                "chunk-1",
                "doc-1",
                null,
                0,
                text,
                text,
                text,
                "Title",
                "Section",
                language,
                KnowledgeDomain.GENERAL,
                List.of(),
                Map.of("source", "source.md")
        );
    }
}
