package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import org.junit.jupiter.api.Test;

class CrossReferenceExtractorTest {

    private final CrossReferenceExtractor extractor =
            new CrossReferenceExtractor();

    @Test
    void extractsRussianInstrumentalArticleReference() {
        var references = extractor.extractTyped(
                "За исключением случаев, предусмотренных статьёй 48 настоящего договора."
        );

        assertThat(references)
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.type())
                            .isEqualTo(CrossReferenceType.ARTICLE);
                    assertThat(reference.canonicalValue()).isEqualTo("48");
                    assertThat(reference.language()).isEqualTo("ru");
                });
    }
}
