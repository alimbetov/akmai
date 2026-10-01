package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MultilingualRetrievalQualityBaselineTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void baselineMetricsAreDeterministic(
            String language,
            List<String> ranked,
            Set<String> relevant,
            double minRecallAt5,
            double minMrr,
            double minNdcgAt5
    ) {
        assertThat(RetrievalQualityMetrics.recallAtK(ranked, relevant, 5))
                .isGreaterThanOrEqualTo(minRecallAt5);
        assertThat(RetrievalQualityMetrics.reciprocalRank(ranked, relevant))
                .isGreaterThanOrEqualTo(minMrr);
        assertThat(RetrievalQualityMetrics.ndcgAtK(ranked, relevant, 5))
                .isGreaterThanOrEqualTo(minNdcgAt5);
    }

    static List<Arguments> cases() {
        return List.of(
                Arguments.of(
                        "kk",
                        List.of("kk-noise", "kk-law-12", "kk-law-12-p2", "other"),
                        Set.of("kk-law-12", "kk-law-12-p2"),
                        1.0, 0.5, 0.69
                ),
                Arguments.of(
                        "ru",
                        List.of("ru-med-dose", "ru-noise", "ru-med-route"),
                        Set.of("ru-med-dose", "ru-med-route"),
                        1.0, 1.0, 0.91
                ),
                Arguments.of(
                        "en",
                        List.of("noise", "en-policy", "other"),
                        Set.of("en-policy"),
                        1.0, 0.5, 0.63
                ),
                Arguments.of(
                        "zh",
                        List.of("zh-law", "noise", "other"),
                        Set.of("zh-law"),
                        1.0, 1.0, 1.0
                )
        );
    }
}
