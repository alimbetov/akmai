package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AnswerGroundingVerifierTest {

    private final AnswerGroundingVerifier verifier =
            new AnswerGroundingVerifier();

    @Test
    void rejectsFactualSentenceWithoutCitation() {
        var result = verifier.verify(
                "The dose is 10 mg.",
                List.of(hit("The recommended dose is 10 mg."))
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.unsupportedClaimCount()).isEqualTo(1);
        assertThat(result.claims()).singleElement()
                .extracting(AnswerGroundingVerifier.ClaimValidation::status)
                .isEqualTo(
                        AnswerGroundingVerifier.ClaimStatus.MISSING_CITATION
                );
    }

    @Test
    void rejectsCitationOnlyAnswerAsHavingNoFactualClaims() {
        var result = verifier.verify(
                "[SOURCE 1]",
                List.of(hit("Evidence"))
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.claims()).isEmpty();
    }

    @Test
    void rejectsUncitedBulletEvenWhenLaterBulletHasCitation() {
        var result = verifier.verify(
                "- First factual claim\n- Second factual claim [SOURCE 1]",
                List.of(hit("Second factual claim"))
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.unsupportedClaimCount()).isEqualTo(1);
        assertThat(result.claims())
                .extracting(AnswerGroundingVerifier.ClaimValidation::status)
                .containsExactly(
                        AnswerGroundingVerifier.ClaimStatus.MISSING_CITATION,
                        AnswerGroundingVerifier.ClaimStatus.SUPPORTED
                );
    }

    @Test
    void rejectsNumericClaimThatCitationDoesNotSupport() {
        var result = verifier.verify(
                "The recommended dose is 20 mg [SOURCE 1].",
                List.of(hit("The recommended dose is 10 mg."))
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
        assertThat(result.claims()).singleElement()
                .extracting(AnswerGroundingVerifier.ClaimValidation::status)
                .isEqualTo(
                        AnswerGroundingVerifier.ClaimStatus.NUMERIC_MISMATCH
                );
    }

    @Test
    void acceptsMultipleNumericValuesWhenCitedEvidenceContainsThemInOrder() {
        var result = verifier.verify(
                "Use 10 mg for 30 days [SOURCE 1].",
                List.of(hit(
                        "The recommended dose is 10 mg and the duration is 30 days."
                ))
        );

        assertThat(result.grounded()).isTrue();
        assertThat(result.unsupportedClaimCount()).isZero();
        assertThat(result.numericMismatchCount()).isZero();
    }

    @Test
    void rejectsSwappedQuantitiesThatPreviouslyCollapsedToSameNumberSet() {
        var result = verifier.verify(
                "Use 10 mg for 30 days [SOURCE 1].",
                List.of(hit("Use 30 mg for 10 days."))
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void rejectsSameNumberWithDifferentUnit() {
        var result = verifier.verify(
                "Use 5 g [SOURCE 1].",
                List.of(hit("Use 5 mg."))
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void rejectsAmbiguousThousandsSeparatorAgainstDecimal() {
        var result = verifier.verify(
                "Use 1,000 mg [SOURCE 1].",
                List.of(hit("Use 1.0 mg."))
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void acceptsUnambiguousCrossLocaleDecimalEquivalence() {
        var result = verifier.verify(
                "Use 0,5 mg [SOURCE 1].",
                List.of(hit("Use 0.5 mg.")),
                "ru"
        );

        assertThat(result.grounded()).isTrue();
        assertThat(result.numericMismatchCount()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multilingualClaims")
    void supportsAllProductionLanguages(
            String language,
            String answer,
            String evidence
    ) {
        var result = verifier.verify(
                answer,
                List.of(hit(evidence))
        );

        assertThat(result.grounded())
                .as("grounding for %s", language)
                .isTrue();
    }

    static Stream<Arguments> multilingualClaims() {
        return Stream.of(
                Arguments.of(
                        "kk",
                        "Ұсынылатын доза 10 мг [SOURCE 1].",
                        "Ұсынылатын доза 10 мг."
                ),
                Arguments.of(
                        "ru",
                        "Рекомендуемая доза 10 мг [SOURCE 1].",
                        "Рекомендуемая доза 10 мг."
                ),
                Arguments.of(
                        "en",
                        "The recommended dose is 10 mg [SOURCE 1].",
                        "The recommended dose is 10 mg."
                ),
                Arguments.of(
                        "zh",
                        "推荐剂量为10毫克 [SOURCE 1]。",
                        "推荐剂量为10毫克。"
                ),
                Arguments.of(
                        "de",
                        "Die empfohlene Dosis beträgt 10 mg [SOURCE 1].",
                        "Die empfohlene Dosis beträgt 10 mg."
                ),
                Arguments.of(
                        "fr",
                        "La dose recommandée est de 10 mg [SOURCE 1].",
                        "La dose recommandée est de 10 mg."
                ),
                Arguments.of(
                        "es",
                        "La dosis recomendada es de 10 mg [SOURCE 1].",
                        "La dosis recomendada es de 10 mg."
                ),
                Arguments.of(
                        "pt",
                        "A dose recomendada é de 10 mg [SOURCE 1].",
                        "A dose recomendada é de 10 mg."
                ),
                Arguments.of(
                        "it",
                        "La dose raccomandata è di 10 mg [SOURCE 1].",
                        "La dose raccomandata è di 10 mg."
                ),
                Arguments.of(
                        "tr",
                        "Önerilen doz 10 mg'dır [SOURCE 1].",
                        "Önerilen doz 10 mg'dır."
                ),
                Arguments.of(
                        "el",
                        "Η συνιστώμενη δόση είναι 10 mg [SOURCE 1].",
                        "Η συνιστώμενη δόση είναι 10 mg."
                )
        );
    }

    private RetrievalHit hit(String text) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc",
                "chunk",
                text,
                Map.of(
                        "source", "source.md",
                        "language", "en",
                        "sectionPath", "section"
                )
        );
    }
}
