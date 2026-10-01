package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MultilingualRetrievalQualityRegressionTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void finalPhaseBDoesNotRegressRetrievalQuality(
            String language,
            List<String> baselineRanked,
            List<String> finalRanked,
            Set<String> relevant
    ) {
        double baselineRecall = RetrievalQualityMetrics.recallAtK(
                baselineRanked, relevant, 5
        );
        double baselineMrr = RetrievalQualityMetrics.reciprocalRank(
                baselineRanked, relevant
        );
        double baselineNdcg = RetrievalQualityMetrics.ndcgAtK(
                baselineRanked, relevant, 5
        );

        double finalRecall = RetrievalQualityMetrics.recallAtK(
                finalRanked, relevant, 5
        );
        double finalMrr = RetrievalQualityMetrics.reciprocalRank(
                finalRanked, relevant
        );
        double finalNdcg = RetrievalQualityMetrics.ndcgAtK(
                finalRanked, relevant, 5
        );

        assertThat(finalRecall).isGreaterThanOrEqualTo(baselineRecall);
        assertThat(finalMrr).isGreaterThanOrEqualTo(baselineMrr);
        assertThat(finalNdcg).isGreaterThanOrEqualTo(baselineNdcg);
        assertThat(finalRecall).isGreaterThanOrEqualTo(1.0);
    }

    static List<Arguments> cases() {
        return List.of(
                Arguments.of(
                        "kk",
                        List.of("kk-noise", "kk-law-12", "kk-law-12-p2"),
                        List.of("kk-law-12", "kk-law-12-p2", "kk-noise"),
                        Set.of("kk-law-12", "kk-law-12-p2")
                ),
                Arguments.of(
                        "ru",
                        List.of("ru-med-dose", "ru-noise", "ru-med-route"),
                        List.of("ru-med-dose", "ru-med-route", "ru-noise"),
                        Set.of("ru-med-dose", "ru-med-route")
                ),
                Arguments.of(
                        "en",
                        List.of("noise", "en-policy", "other"),
                        List.of("en-policy", "noise", "other"),
                        Set.of("en-policy")
                ),
                Arguments.of(
                        "zh",
                        List.of("zh-law", "noise", "other"),
                        List.of("zh-law", "noise", "other"),
                        Set.of("zh-law")
                )
        );
    }
}
