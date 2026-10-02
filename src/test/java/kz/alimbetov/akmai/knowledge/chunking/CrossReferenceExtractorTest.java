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
    @Test
    void normalizesEquivalentArticleReferencesAcrossSupportedLanguages() {
        CrossReferenceExtractor extractor = new CrossReferenceExtractor();

        var ru = extractor.extractTyped("См. статью 25 настоящего закона.");
        var en = extractor.extractTyped("See Article 25 of this law.");
        var kk = extractor.extractTyped("Қараңыз 25-бап бойынша.");
        var zh = extractor.extractTyped("参见第二十五条。");

        assertThat(ru).singleElement().satisfies(reference -> {
            assertThat(reference.type())
                    .isEqualTo(kz.alimbetov.akmai.knowledge.reference.CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
        assertThat(en).singleElement().satisfies(reference -> {
            assertThat(reference.type())
                    .isEqualTo(kz.alimbetov.akmai.knowledge.reference.CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
        assertThat(kk).singleElement().satisfies(reference -> {
            assertThat(reference.type())
                    .isEqualTo(kz.alimbetov.akmai.knowledge.reference.CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
        assertThat(zh).singleElement().satisfies(reference -> {
            assertThat(reference.type())
                    .isEqualTo(kz.alimbetov.akmai.knowledge.reference.CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
    }

}
