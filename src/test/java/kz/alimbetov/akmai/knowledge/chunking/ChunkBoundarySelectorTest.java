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
}
