package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Golden decision tests: validate the boundary selected among competing candidates. */
class SmartDelimiterDecisionMatrixTest {

    private static final List<Spec> LANGUAGES = List.of(
            new Spec("kk", '.', ',', "Кіріспе", "1-бап Жалпы ережелер"),
            new Spec("ru", '.', ',', "Введение", "Статья 5 Общие положения"),
            new Spec("en", '.', ',', "Introduction", "Article 5 General provisions"),
            new Spec("zh", '。', '，', "前言", "第一条总则"),
            new Spec("de", '.', ',', "Einleitung", "§ 5 Haftung"),
            new Spec("fr", '.', ',', "Introduction", "Article 5 Dispositions générales"),
            new Spec("es", '.', ',', "Introducción", "Artículo 5 Disposiciones generales"),
            new Spec("pt", '.', ',', "Introdução", "Artigo 5 Disposições gerais"),
            new Spec("it", '.', ',', "Introduzione", "Articolo 5 Disposizioni generali"),
            new Spec("tr", '.', ',', "Giriş", "Madde 5 Genel hükümler"),
            new Spec("el", ';', ',', "Εισαγωγή", "Άρθρο 5 Γενικές διατάξεις")
    );

    @TestFactory
    Stream<DynamicTest> selectsStrongestBoundaryForEveryLanguage() {
        return LANGUAGES.stream().flatMap(spec -> Stream.of(
                DynamicTest.dynamicTest(
                        spec.code() + " prefers sentence over weak punctuation",
                        () -> sentenceWins(spec)
                ),
                DynamicTest.dynamicTest(
                        spec.code() + " prefers native structural marker",
                        () -> structureWins(spec)
                ),
                DynamicTest.dynamicTest(
                        spec.code() + " skips technical false positives",
                        () -> protectedTechnicalTokensLose(spec)
                )
        ));
    }

    private void sentenceWins(Spec spec) {
        String separator = spec.code().equals("zh") ? "" : " ";
        String text = "alpha"
                + spec.weak()
                + separator
                + "beta gamma"
                + spec.sentence()
                + separator
                + "tail continuation";
        int expected = text.indexOf(spec.sentence()) + 1;

        int actual = ChunkBoundarySelector.bestBoundary(
                text,
                Math.max(1, text.indexOf(spec.weak()) + 1),
                text.length() - 1,
                spec.code()
        );

        assertThat(actual).isEqualTo(expected);
    }

    private void structureWins(Spec spec) {
        String text = spec.intro() + "\n" + spec.structuralMarker() + " continuation";
        int expected = text.indexOf('\n') + 1;

        int actual = ChunkBoundarySelector.bestBoundary(
                text,
                Math.max(1, expected - 2),
                text.length() - 1,
                spec.code()
        );

        assertThat(actual).isEqualTo(expected);
        assertThat(ChunkBoundarySelector.rank(text, actual, spec.code()))
                .isEqualTo(ChunkBoundarySelector.RANK_STRUCTURAL);
    }

    private void protectedTechnicalTokensLose(Spec spec) {
        String separator = spec.code().equals("zh") ? "" : " ";
        String text = "v2.1"
                + separator
                + "schema:value"
                + separator
                + "12:30"
                + separator
                + "final statement"
                + spec.sentence()
                + separator
                + "tail";
        int expected = text.lastIndexOf(spec.sentence()) + 1;

        int actual = ChunkBoundarySelector.bestBoundary(
                text,
                1,
                text.length() - 1,
                spec.code()
        );

        assertThat(actual).isEqualTo(expected);
    }

    private record Spec(
            String code,
            char sentence,
            char weak,
            String intro,
            String structuralMarker
    ) {
    }
}
