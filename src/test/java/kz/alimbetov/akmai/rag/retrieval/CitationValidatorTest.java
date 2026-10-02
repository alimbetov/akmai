package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    @Test
    void returnsOnlyActuallyCitedValidSourcesAndRemovesInvalidMarker() {
        List<RetrievalHit> context = List.of(
                hit("doc-1", "chunk-1", "law.md"),
                hit("doc-2", "chunk-2", "policy.md")
        );

        CitationValidator.CitationValidation result = validator.validate(
                "Fact [SOURCE 2]. Hallucinated [SOURCE 99].",
                context
        );

        assertThat(result.answer()).contains("[SOURCE 2]").doesNotContain("[SOURCE 99]");
        assertThat(result.citedSources()).hasSize(1);
        assertThat(result.citedSources().getFirst().number()).isEqualTo(2);
        assertThat(result.citedSources().getFirst().chunkId()).isEqualTo("chunk-2");
        assertThat(result.invalidSourceNumbers()).containsExactly(99);
    }

    @Test
    void removesFlexibleWhitespaceAndOversizedInvalidMarkersWithoutThrowing() {
        List<RetrievalHit> context = List.of(
                hit("doc-1", "chunk-1", "law.md")
        );
        String huge = "9".repeat(200);

        CitationValidator.CitationValidation result = validator.validate(
                "Valid [SOURCE   1]. Invalid [SOURCE   99]. Huge [SOURCE " + huge + "].",
                context
        );

        assertThat(result.answer())
                .contains("[SOURCE   1]")
                .doesNotContain("[SOURCE   99]")
                .doesNotContain(huge);
        assertThat(result.citedSources()).singleElement()
                .extracting(SourceRef::number)
                .isEqualTo(1);
    }

    private RetrievalHit hit(String documentId, String chunkId, String source) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                documentId,
                chunkId,
                "text",
                Map.of("source", source, "language", "ru", "sectionPath", "s")
        );
    }
}
