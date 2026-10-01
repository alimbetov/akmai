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
}
