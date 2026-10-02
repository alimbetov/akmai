package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import org.junit.jupiter.api.Test;

class QueryDecomposerTest {

    private final QueryDecomposer decomposer =
            new QueryDecomposer(new TextNormalizer());

    @Test
    void preservesOriginalAndCreatesEnglishSubqueries() {
        assertThat(decomposer.decompose(
                "What dosage applies and what monitoring is required?"
        )).containsExactly(
                "What dosage applies and what monitoring is required?",
                "What dosage applies",
                "what monitoring is required?"
        );
    }

    @Test
    void decomposesRussianKazakhAndChineseMultiIntentQueries() {
        assertThat(decomposer.decompose(
                "Какая дозировка и какие противопоказания?"
        )).contains(
                "Какая дозировка",
                "какие противопоказания?"
        );

        assertThat(decomposer.decompose(
                "Қандай доза және қандай мониторинг қажет?"
        )).contains(
                "Қандай доза",
                "қандай мониторинг қажет?"
        );

        assertThat(decomposer.decompose(
                "剂量是多少以及禁忌是什么？"
        )).contains(
                "剂量是多少",
                "禁忌是什么？"
        );
    }


    @Test
    void splitsChineseSentencesWithoutWhitespace() {
        assertThat(decomposer.decompose(
                "剂量是多少？需要监测什么？"
        )).containsExactly(
                "剂量是多少？需要监测什么？",
                "剂量是多少？",
                "需要监测什么？"
        );
    }

    @Test
    void doesNotSplitOrdinaryConjunctionWithoutIndependentIntents() {
        assertThat(decomposer.decompose(
                "What are the risks and benefits of the treatment?"
        )).containsExactly(
                "What are the risks and benefits of the treatment?"
        );
    }

    @Test
    void boundsAndDeduplicatesFanOut() {
        String question = String.join(" ", List.of(
                "What is item one?",
                "What is item two?",
                "What is item three?",
                "What is item four?",
                "What is item five?",
                "What is item six?",
                "What is item seven?",
                "What is item eight?",
                "What is item nine?",
                "What is item ten?"
        ));

        assertThat(decomposer.decompose(question))
                .hasSize(QueryDecomposer.MAX_SEGMENTS)
                .doesNotHaveDuplicates();
    }
    @Test
    void overflowKeepsOriginalCatchAllAndReportsDroppedSubqueries() {
        String question = String.join(" ", java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "What is item " + index + "?")
                .toList());

        QueryDecompositionResult result = decomposer.decomposeDetailed(question);

        assertThat(result.units()).hasSize(QueryDecomposer.MAX_SEGMENTS);
        assertThat(result.units().getFirst()).isEqualTo(question);
        assertThat(result.overflowCount()).isGreaterThan(0);
    }

    @Test
    void legalAbbreviationsStayAttachedToTheirNumbers() {
        QueryDecompositionResult result = decomposer.decomposeDetailed(
                "Ст. 25 применяется. П. 3 содержит исключение. Art. 42 applies. No. 7 is referenced."
        );

        assertThat(result.units())
                .anyMatch(unit -> unit.contains("Ст. 25 применяется."))
                .anyMatch(unit -> unit.contains("П. 3 содержит исключение."))
                .anyMatch(unit -> unit.contains("Art. 42 applies."))
                .anyMatch(unit -> unit.contains("No. 7 is referenced."));
        assertThat(result.units()).noneMatch(unit -> unit.equals("Ст.")
                || unit.equals("П.")
                || unit.equals("Art.")
                || unit.equals("No."));
    }

}
