package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                    assertThat(reference.targetScope())
                            .isEqualTo(ReferenceTargetScope.SAME_DOCUMENT);
                });
    }

    @Test
    void normalizesEquivalentArticleReferencesAcrossSupportedLanguages() {
        var ru = extractor.extractTyped("См. статью 25 настоящего закона.");
        var en = extractor.extractTyped("See Article 25 of this law.");
        var kk = extractor.extractTyped("Қараңыз 25-бап бойынша.");
        var zh = extractor.extractTyped("参见第二十五条。");

        assertThat(ru).singleElement().satisfies(reference -> {
            assertThat(reference.type()).isEqualTo(CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
        assertThat(en).singleElement().satisfies(reference -> {
            assertThat(reference.type()).isEqualTo(CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
        assertThat(kk).singleElement().satisfies(reference -> {
            assertThat(reference.type()).isEqualTo(CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
        assertThat(zh).singleElement().satisfies(reference -> {
            assertThat(reference.type()).isEqualTo(CrossReferenceType.ARTICLE);
            assertThat(reference.canonicalValue()).isEqualTo("25");
        });
    }

    @Test
    void leadingWhitespaceDeclarationIsNotEmittedAsReference() {
        assertThat(extractor.extractTyped(
                "  \n  Статья 5. Общие положения"
        )).isEmpty();
    }

    @Test
    void expandsBoundedIntegerRange() {
        var references = extractor.extractTyped(
                "См. статьи 5–7 настоящего закона.",
                "ru"
        );

        assertThat(references)
                .extracting(reference -> reference.canonicalValue())
                .containsExactly("5", "6", "7");
    }

    @Test
    void supportsRomanNumeralsForStructuralReferences() {
        var references = extractor.extractTyped(
                "See Chapter IV and Section XII.",
                "en"
        );

        assertThat(references)
                .extracting(reference -> reference.type() + ":"
                        + reference.canonicalValue())
                .containsExactly(
                        "CHAPTER:4",
                        "SECTION:12"
                );
    }

    @Test
    void expandsChineseStructureAndRanges() {
        var structure = extractor.extractTyped(
                "参见第三章第五条。",
                "zh"
        );
        var range = extractor.extractTyped(
                "参见第五条至第七条。",
                "zh"
        );

        assertThat(structure)
                .extracting(reference -> reference.type() + ":"
                        + reference.canonicalValue())
                .containsExactly(
                        "CHAPTER:3",
                        "ARTICLE:5"
                );
        assertThat(range)
                .extracting(reference -> reference.canonicalValue())
                .containsExactly("5", "6", "7");
    }

    @ParameterizedTest
    @CsvSource({
            "de, 'Siehe Artikel 12.'",
            "fr, 'Voir article 12.'",
            "es, 'Véase artículo 12.'",
            "pt, 'Ver artigo 12.'",
            "it, 'Vedi articolo 12.'",
            "tr, 'Bkz. madde 12.'",
            "el, 'Βλέπε άρθρο 12.'"
    })
    void usesDocumentLanguageHintForExtendedLanguages(
            String language,
            String text
    ) {
        var references = extractor.extractTyped(text, language);

        assertThat(references)
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.type())
                            .isEqualTo(CrossReferenceType.ARTICLE);
                    assertThat(reference.canonicalValue()).isEqualTo("12");
                    assertThat(reference.language()).isEqualTo(language);
                });
    }

    @Test
    void extractsNamedObjects() {
        var references = extractor.extractTyped(
                "See Appendix A, Table 4 and Figure 2.",
                "en"
        );

        assertThat(references)
                .extracting(reference -> reference.type() + ":"
                        + reference.canonicalValue())
                .containsExactly(
                        "APPENDIX:A",
                        "TABLE:4",
                        "FIGURE:2"
                );
    }

    @Test
    void classifiesExplicitCodeAsExternalDocumentScope() {
        var references = extractor.extractTyped(
                "См. ст. 25 ГК РК.",
                "ru"
        );

        assertThat(references)
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.targetScope())
                            .isEqualTo(ReferenceTargetScope.EXPLICIT_DOCUMENT);
                    assertThat(reference.targetDocumentId())
                            .isEqualTo("ГК РК");
                });
    }

    @Test
    void extractsExternalStandardsAndTechnicalDocuments() {
        var references = extractor.extractTyped(
                "See ISO 9001:2015 and RFC 7231.",
                "en"
        );

        assertThat(references)
                .extracting(reference -> reference.type() + ":"
                        + reference.canonicalValue())
                .containsExactly(
                        "EXTERNAL_STANDARD:ISO 9001:2015",
                        "EXTERNAL_TECHNICAL:RFC 7231"
                );
        assertThat(references)
                .allSatisfy(reference -> assertThat(reference.targetScope())
                        .isEqualTo(ReferenceTargetScope.EXPLICIT_DOCUMENT));
    }

    @Test
    void retainsExactSourceOffsets() {
        var reference = extractor.extractTyped(
                "xx Article 12 yy",
                "en"
        ).getFirst();

        assertThat(reference.startOffset()).isEqualTo(3);
        assertThat(reference.endOffset()).isEqualTo(13);
        assertThat("xx Article 12 yy".substring(
                reference.startOffset(),
                reference.endOffset()
        )).isEqualTo(reference.rawValue());
    }
}
