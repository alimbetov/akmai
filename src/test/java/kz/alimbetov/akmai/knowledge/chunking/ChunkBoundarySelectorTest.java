package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkBoundarySelectorTest {

    @Test
    void prefersSentenceBoundaryOverNearbyWhitespace() {
        String text = "Alpha beta gamma. Delta epsilon zeta eta theta.";
        int boundary = ChunkBoundarySelector.bestBoundary(
                text,
                8,
                34,
                "en"
        );

        assertThat(text.substring(0, boundary).trim())
                .isEqualTo("Alpha beta gamma.");
        assertThat(ChunkBoundarySelector.rank(text, boundary, "en"))
                .isEqualTo(ChunkBoundarySelector.RANK_SENTENCE);
    }

    @Test
    void knownAbbreviationIsNotSentenceBoundary() {
        String text = "См. приложение и продолжение текста.";
        int boundary = text.indexOf(" приложение");

        assertThat(ChunkBoundarySelector.rank(text, boundary, "ru"))
                .isNotEqualTo(ChunkBoundarySelector.RANK_SENTENCE);
    }

    @Test
    void decimalPointIsNotSentenceBoundary() {
        String text = "Версия 3.14 доступна для проверки.";
        int boundary = text.indexOf('.') + 1;

        assertThat(ChunkBoundarySelector.rank(text, boundary, "ru"))
                .isEqualTo(ChunkBoundarySelector.RANK_NONE);
    }

    @Test
    void paragraphAndListBoundaryHasHighestRank() {
        String text = "Вводный текст.\n\n1. Первый пункт списка";
        int boundary = text.indexOf("1. Первый");

        assertThat(ChunkBoundarySelector.rank(text, boundary, "ru"))
                .isEqualTo(ChunkBoundarySelector.RANK_STRUCTURAL);
    }

    @Test
    void recognizesNativeStructuralMarkersForEverySupportedLanguage() {
        assertStructural("kk", "Кіріспе\n1-бап Жалпы ережелер");
        assertStructural("ru", "Введение\nСтатья 5. Общие положения");
        assertStructural("en", "Introduction\nArticle 5. General provisions");
        assertStructural("zh", "前言\n第一条总则");
        assertStructural("de", "Einleitung\n§ 5 Haftung");
        assertStructural("fr", "Introduction\nArticle 5 Dispositions générales");
        assertStructural("es", "Introducción\nArtículo 5 Disposiciones generales");
        assertStructural("pt", "Introdução\nArtigo 5 Disposições gerais");
        assertStructural("it", "Introduzione\nArticolo 5 Disposizioni generali");
        assertStructural("tr", "Giriş\nMadde 5 Genel hükümler");
        assertStructural("el", "Εισαγωγή\nΆρθρο 5 Γενικές διατάξεις");
    }

    @Test
    void protectsNumericCommaUrlVersionTimeInitialsAndTechnicalIdentifiers() {
        assertRank("Значение 3,14 применяется", ',', "ru", ChunkBoundarySelector.RANK_NONE);
        assertRank("visit example.com/path now", '.', "en", ChunkBoundarySelector.RANK_NONE);
        assertRank("release v2.1 is stable", '.', "en", ChunkBoundarySelector.RANK_NONE);
        assertRank("Время 12:30 указано", ':', "ru", ChunkBoundarySelector.RANK_NONE);
        assertRank("field schema:value remains atomic", ':', "en", ChunkBoundarySelector.RANK_NONE);
        assertRank("identifier urn:uuid continues", ':', "en", ChunkBoundarySelector.RANK_NONE);

        String initial = "Author A. Smith continues";
        int initialBoundary = initial.indexOf(". ") + 1;
        assertThat(ChunkBoundarySelector.rank(initial, initialBoundary, "en"))
                .isEqualTo(ChunkBoundarySelector.RANK_WHITESPACE);
    }

    @Test
    void proseColonRemainsClauseBoundary() {
        String text = "Condition: next paragraph explains the rule";
        int boundary = text.indexOf(':') + 1;

        assertThat(ChunkBoundarySelector.rank(text, boundary, "en"))
                .isEqualTo(ChunkBoundarySelector.RANK_CLAUSE);
    }

    @Test
    void onlyEndOfPunctuationClusterIsSentenceBoundary() {
        String text = "Really?! Next sentence";
        int questionBoundary = text.indexOf('?') + 1;
        int exclamationBoundary = text.indexOf('!') + 1;

        assertThat(ChunkBoundarySelector.rank(text, questionBoundary, "en"))
                .isEqualTo(ChunkBoundarySelector.RANK_NONE);
        assertThat(ChunkBoundarySelector.rank(text, exclamationBoundary, "en"))
                .isEqualTo(ChunkBoundarySelector.RANK_SENTENCE);
    }

    @Test
    void chineseEnumerationCommaIsWeakButValidBoundary() {
        String text = "第一项、第二项继续说明";
        int boundary = text.indexOf('、') + 1;

        assertThat(ChunkBoundarySelector.rank(text, boundary, "zh"))
                .isEqualTo(ChunkBoundarySelector.RANK_COMMA);
    }

    private void assertStructural(String language, String text) {
        int boundary = text.indexOf('\n') + 1;
        assertThat(ChunkBoundarySelector.rank(text, boundary, language))
                .as("language=%s text=%s", language, text)
                .isEqualTo(ChunkBoundarySelector.RANK_STRUCTURAL);
    }

    private void assertRank(
            String text,
            char punctuation,
            String language,
            int expectedRank
    ) {
        int boundary = text.indexOf(punctuation) + 1;
        assertThat(ChunkBoundarySelector.rank(text, boundary, language))
                .as("language=%s text=%s", language, text)
                .isEqualTo(expectedRank);
    }
}
