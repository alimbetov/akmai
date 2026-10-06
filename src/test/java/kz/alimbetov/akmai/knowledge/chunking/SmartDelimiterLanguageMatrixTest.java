package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class SmartDelimiterLanguageMatrixTest {

    private static final List<LanguageSpec> LANGUAGES = List.of(
            new LanguageSpec("kk", "Бірінші сөйлем", "Келесі бөлім", "мыс.", '.', '?', ',', ':'),
            new LanguageSpec("ru", "Первое предложение", "Следующий раздел", "ст.", '.', '?', ',', ':'),
            new LanguageSpec("en", "First sentence", "Next section", "dr.", '.', '?', ',', ':'),
            new LanguageSpec("zh", "第一句话", "下一部分", null, '。', '？', '，', '：'),
            new LanguageSpec("de", "Erster Satz", "Nächster Abschnitt", "dr.", '.', '?', ',', ':'),
            new LanguageSpec("fr", "Première phrase", "Section suivante", "dr.", '.', '?', ',', ':'),
            new LanguageSpec("es", "Primera frase", "Sección siguiente", "dr.", '.', '?', ',', ':'),
            new LanguageSpec("pt", "Primeira frase", "Próxima seção", "dr.", '.', '?', ',', ':'),
            new LanguageSpec("it", "Prima frase", "Sezione successiva", "dott.", '.', '?', ',', ':'),
            new LanguageSpec("tr", "İlk cümle", "Sonraki bölüm", "dr.", '.', '?', ',', ':'),
            new LanguageSpec("el", "Πρώτη πρόταση", "Επόμενη ενότητα", "δρ.", '.', ';', ',', ':')
    );

    @TestFactory
    Stream<DynamicTest> oneHundredBoundaryCasesPerLanguage() {
        return LANGUAGES.stream().flatMap(spec -> {
            List<BoundaryCase> cases = casesFor(spec);
            if (cases.size() != 100) {
                throw new IllegalStateException(
                        spec.code() + " must define exactly 100 cases, got " + cases.size()
                );
            }
            return cases.stream().map(testCase -> DynamicTest.dynamicTest(
                    "[" + spec.code() + "] " + testCase.name(),
                    testCase.assertion()
            ));
        });
    }

    private List<BoundaryCase> casesFor(LanguageSpec spec) {
        List<BoundaryCase> cases = new ArrayList<>(100);

        addSentenceCases(cases, spec, 15);
        addQuestionCases(cases, spec, 10);
        addClauseCases(cases, spec, 10);
        addCommaCases(cases, spec, 10);
        addParagraphCases(cases, spec, 10);
        addListCases(cases, spec, 10);
        addDecimalCases(cases, spec, 10);
        addAbbreviationOrReferenceCases(cases, spec, 10);
        addClosingPunctuationCases(cases, spec, 10);
        addUnicodeSafetyCases(cases, spec, 5);

        return List.copyOf(cases);
    }

    private void addSentenceCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            String left = spec.first() + " " + variant;
            String text = join(left + spec.sentenceTerminal(), spec.second(), spec);
            int boundary = boundaryAfter(text, spec.sentenceTerminal());
            cases.add(rankCase(
                    "sentence-%02d".formatted(variant),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_SENTENCE
            ));
        }
    }

    private void addQuestionCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            String text = join(
                    spec.first() + " " + variant + spec.questionTerminal(),
                    spec.second(),
                    spec
            );
            int boundary = boundaryAfter(text, spec.questionTerminal());
            cases.add(rankCase(
                    "question-%02d".formatted(variant),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_SENTENCE
            ));
        }
    }

    private void addClauseCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            String text = join(
                    spec.first() + " " + variant + spec.clauseTerminal(),
                    spec.second(),
                    spec
            );
            int boundary = boundaryAfter(text, spec.clauseTerminal());
            cases.add(rankCase(
                    "clause-%02d".formatted(variant),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_CLAUSE
            ));
        }
    }

    private void addCommaCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            String text = join(
                    spec.first() + " " + variant + spec.comma(),
                    spec.second(),
                    spec
            );
            int boundary = boundaryAfter(text, spec.comma());
            cases.add(rankCase(
                    "comma-%02d".formatted(variant),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_COMMA
            ));
        }
    }

    private void addParagraphCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            String right = spec.second() + " " + variant;
            String text = spec.first() + "\n\n" + right;
            int boundary = text.indexOf(right);
            cases.add(rankCase(
                    "paragraph-%02d".formatted(variant),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_STRUCTURAL
            ));
        }
    }

    private void addListCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        List<String> markers = List.of(
                "- ", "– ", "— ", "• ", "1. ", "2) ", "a) ",
                "(b) ", "I. ", "(ii) "
        );
        for (int variant = 0; variant < count; variant++) {
            String item = markers.get(variant) + spec.second();
            String text = spec.first() + "\n" + item;
            int boundary = text.indexOf(item);
            cases.add(rankCase(
                    "list-%02d".formatted(variant + 1),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_STRUCTURAL
            ));
        }
    }

    private void addDecimalCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            String number = variant + "." + (variant + 10);
            String text = prefix(spec) + number + suffix(spec);
            int boundary = text.indexOf('.') + 1;
            cases.add(rankCase(
                    "decimal-%02d".formatted(variant),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_NONE
            ));
        }
    }

    private void addAbbreviationOrReferenceCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            if (spec.abbreviation() != null) {
                String text = prefix(spec)
                        + spec.abbreviation()
                        + " "
                        + spec.second()
                        + " "
                        + variant;
                int terminal = text.indexOf(
                        spec.abbreviation()
                ) + spec.abbreviation().length();
                cases.add(rankCase(
                        "abbreviation-%02d".formatted(variant),
                        text,
                        terminal,
                        spec.code(),
                        ChunkBoundarySelector.RANK_WHITESPACE
                ));
            } else {
                String reference = variant + "." + (variant + 1);
                String text = "第" + reference + "条规定继续适用";
                int terminal = text.indexOf('.') + 1;
                cases.add(rankCase(
                        "reference-%02d".formatted(variant),
                        text,
                        terminal,
                        spec.code(),
                        ChunkBoundarySelector.RANK_NONE
                ));
            }
        }
    }

    private void addClosingPunctuationCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        List<String> closers = List.of("”", "’", "»", ")", "]");
        for (int variant = 0; variant < count; variant++) {
            String closer = closers.get(variant % closers.size());
            String left = spec.first() + (variant + 1) + spec.sentenceTerminal() + closer;
            String text = join(left, spec.second(), spec);
            int boundary = boundaryAfterClosing(text, closer);
            cases.add(rankCase(
                    "closing-punctuation-%02d".formatted(variant + 1),
                    text,
                    boundary,
                    spec.code(),
                    ChunkBoundarySelector.RANK_SENTENCE
            ));
        }
    }

    private void addUnicodeSafetyCases(
            List<BoundaryCase> cases,
            LanguageSpec spec,
            int count
    ) {
        for (int variant = 1; variant <= count; variant++) {
            String text = spec.first() + "🙂" + variant + " " + spec.second();
            int emoji = text.indexOf("🙂");
            int unsafeMiddle = emoji + 1;
            cases.add(new BoundaryCase(
                    "unicode-surrogate-%02d".formatted(variant),
                    () -> {
                        int boundary = ChunkBoundarySelector.bestBoundary(
                                text,
                                Math.max(1, unsafeMiddle - 1),
                                Math.min(text.length() - 1, unsafeMiddle + 1),
                                spec.code()
                        );
                        assertThat(boundary).isBetween(1, text.length() - 1);
                        assertThat(isInsideSurrogatePair(text, boundary)).isFalse();
                    }
            ));
        }
    }

    private BoundaryCase rankCase(
            String name,
            String text,
            int boundary,
            String language,
            int expectedRank
    ) {
        return new BoundaryCase(name, () -> assertThat(
                ChunkBoundarySelector.rank(text, boundary, language)
        ).isEqualTo(expectedRank));
    }

    private String join(String left, String right, LanguageSpec spec) {
        return spec.code().equals("zh") ? left + right : left + " " + right;
    }

    private String prefix(LanguageSpec spec) {
        return spec.code().equals("zh") ? "版本" : spec.first() + " ";
    }

    private String suffix(LanguageSpec spec) {
        return spec.code().equals("zh") ? "继续" : " " + spec.second();
    }

    private int boundaryAfter(String text, char terminal) {
        return text.indexOf(terminal) + Character.charCount(terminal);
    }

    private int boundaryAfterClosing(String text, String closer) {
        return text.indexOf(closer) + closer.length();
    }

    private boolean isInsideSurrogatePair(String text, int boundary) {
        return boundary > 0
                && boundary < text.length()
                && Character.isHighSurrogate(text.charAt(boundary - 1))
                && Character.isLowSurrogate(text.charAt(boundary));
    }

    private record LanguageSpec(
            String code,
            String first,
            String second,
            String abbreviation,
            char sentenceTerminal,
            char questionTerminal,
            char comma,
            char clauseTerminal
    ) {
        private LanguageSpec {
            code = code.toLowerCase(Locale.ROOT);
        }
    }

    private record BoundaryCase(String name, Runnable assertion) {
    }
}
