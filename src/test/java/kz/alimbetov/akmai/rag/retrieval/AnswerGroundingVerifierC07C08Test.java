package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AnswerGroundingVerifierC07C08Test {

    private final AnswerGroundingVerifier verifier =
            new AnswerGroundingVerifier();

    @ParameterizedTest(name = "locale separators: {0}")
    @MethodSource("productionLocaleFormats")
    void acceptsUnambiguousProductionLocaleFormatting(
            String language,
            String formatted,
            String canonicalEvidence
    ) {
        var result = verifier.verify(
                "Amount is " + formatted + " mg [SOURCE 1].",
                List.of(hit(canonicalEvidence + " mg", language)),
                language
        );

        assertThat(result.grounded())
                .as("locale-aware grounding for %s", language)
                .isTrue();
    }

    @Test
    void englishThousandsSeparatorIsNotDecimalSeparator() {
        var result = verifier.verify(
                "The amount is 1,000 mg [SOURCE 1].",
                List.of(hit("The amount is 1.0 mg.", "en")),
                "en"
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void mixedSeparatorsAreParsedByQueryAndSourceLocales() {
        var result = verifier.verify(
                "The amount is 1,234.56 mg [SOURCE 1].",
                List.of(hit("Die Menge beträgt 1.234,56 mg.", "de")),
                "en"
        );

        assertThat(result.grounded()).isTrue();
    }

    @Test
    void localeInconsistentMixedFormatFailsClosed() {
        var result = verifier.verify(
                "The amount is 1.234,56 mg [SOURCE 1].",
                List.of(hit("The amount is 1234.56 mg.", "en")),
                "en"
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void acceptsExplicitEquivalentUnitConversion() {
        var result = verifier.verify(
                "Use 5 g [SOURCE 1].",
                List.of(hit("Use 5000 mg.", "en")),
                "en"
        );

        assertThat(result.grounded()).isTrue();
    }

    @Test
    void rejectsSameLiteralWithDifferentMagnitudeUnit() {
        var result = verifier.verify(
                "Use 5 g [SOURCE 1].",
                List.of(hit("Use 5 mg.", "en")),
                "en"
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void rejectsSwappedMedicalDoseAndDuration() {
        var result = verifier.verify(
                "Use 10 mg for 30 days [SOURCE 1].",
                List.of(hit("Use 30 mg for 10 days.", "en")),
                "en"
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void repeatedClaimValuesRequireRepeatedEvidenceOccurrences() {
        var result = verifier.verify(
                "Use 5 mg, then another 5 mg [SOURCE 1].",
                List.of(hit("Use 5 mg once.", "en")),
                "en"
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void rejectsDateComponentPermutationThatChangesDate() {
        var result = verifier.verify(
                "The deadline is 03/04/2026 [SOURCE 1].",
                List.of(hit("The deadline is 04/03/2026.", "en")),
                "en"
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void acceptsSameDateAcrossUnambiguousIsoAndLocaleForms() {
        var result = verifier.verify(
                "The deadline is 03/04/2026 [SOURCE 1].",
                List.of(hit("The deadline is 2026-03-04.", "en")),
                "en"
        );

        assertThat(result.grounded()).isTrue();
    }

    @Test
    void rejectsFinancialMinimumAgainstMaximumSlot() {
        var result = verifier.verify(
                "Minimum capital is $1,000 [SOURCE 1].",
                List.of(hit("Maximum capital is $1,000.", "en")),
                "en"
        );

        assertThat(result.grounded()).isFalse();
        assertThat(result.numericMismatchCount()).isEqualTo(1);
    }

    @Test
    void acceptsEquivalentFinancialMinimumAcrossLocales() {
        var result = verifier.verify(
                "Minimum capital is €1,500.00 [SOURCE 1].",
                List.of(hit("Mindestens 1.500,00 € Kapital sind erforderlich.", "de")),
                "en"
        );

        assertThat(result.grounded()).isTrue();
    }

    static Stream<Arguments> productionLocaleFormats() {
        return Stream.of(
                Arguments.of("kk", "1 234,56", "1 234,56"),
                Arguments.of("ru", "1 234,56", "1 234,56"),
                Arguments.of("en", "1,234.56", "1,234.56"),
                Arguments.of("zh", "1,234.56", "1,234.56"),
                Arguments.of("de", "1.234,56", "1.234,56"),
                Arguments.of("fr", "1 234,56", "1 234,56"),
                Arguments.of("es", "1.234,56", "1.234,56"),
                Arguments.of("pt", "1.234,56", "1.234,56"),
                Arguments.of("it", "1.234,56", "1.234,56"),
                Arguments.of("tr", "1.234,56", "1.234,56"),
                Arguments.of("el", "1.234,56", "1.234,56")
        );
    }

    private RetrievalHit hit(String text, String language) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc",
                "chunk",
                text,
                Map.of(
                        "source", "source.md",
                        "language", language,
                        "sectionPath", "section"
                )
        );
    }
}
