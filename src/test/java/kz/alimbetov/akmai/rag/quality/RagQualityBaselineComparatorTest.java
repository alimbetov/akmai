package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagQualityBaselineComparatorTest {

    private final RagQualityBaselineComparator comparator =
            new RagQualityBaselineComparator();

    @Test
    void acceptsCandidateWithinAbsoluteAndRegressionBudgets() {
        RagQualityGateSpec spec = spec();
        RagQualitySnapshot baseline = snapshot(metrics(0.95, 0.84, 0.83, 0.01));
        RagQualitySnapshot candidate = snapshot(metrics(0.94, 0.83, 0.82, 0.015));

        var result = comparator.compare(baseline, candidate, spec);

        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.toPolicyQualityReport(true, true, false, candidate))
                .containsEntry("qualityPassed", true)
                .containsEntry("releaseCorpusQualified", true)
                .containsEntry("securityPassed", true)
                .containsEntry("correctnessPassed", true);
    }

    @Test
    void rejectsQualityRegressionEvenWhenCandidateStillLooksGoodAbsolutely() {
        RagQualitySnapshot baseline = snapshot(metrics(0.98, 0.90, 0.89, 0.005));
        RagQualitySnapshot candidate = snapshot(metrics(0.94, 0.86, 0.85, 0.01));

        var result = comparator.compare(baseline, candidate, spec());

        assertThat(result.passed()).isFalse();
        assertThat(result.failures())
                .anyMatch(failure -> failure.metric().equals("recallAt10")
                        && failure.reason().contains("regressed"));
    }

    @Test
    void rejectsFalseAnswerRateIncrease() {
        RagQualitySnapshot baseline = snapshot(metrics(0.95, 0.84, 0.83, 0.005));
        RagQualitySnapshot candidate = snapshot(metrics(0.95, 0.84, 0.83, 0.03));

        var result = comparator.compare(baseline, candidate, spec());

        assertThat(result.passed()).isFalse();
        assertThat(result.failures())
                .anyMatch(failure -> failure.metric().equals("falseAnswerRate"));
    }

    @Test
    void refusesToCompareDifferentCorpora() {
        RagQualitySnapshot baseline = snapshot(
                "corpus-v1",
                metrics(0.95, 0.84, 0.83, 0.005)
        );
        RagQualitySnapshot candidate = snapshot(
                "corpus-v2",
                metrics(0.96, 0.85, 0.84, 0.004)
        );

        var result = comparator.compare(baseline, candidate, spec());

        assertThat(result.passed()).isFalse();
        assertThat(result.failures())
                .anyMatch(failure -> failure.metric().equals("corpusVersion"));
    }

    private RagQualityGateSpec spec() {
        return new RagQualityGateSpec(
                Map.of(
                        "recallAt10", 0.90,
                        "mrr", 0.80,
                        "ndcgAt10", 0.80,
                        "contextRecall", 0.80,
                        "abstentionPrecision", 0.85,
                        "abstentionRecall", 0.80
                ),
                Map.of(
                        "falseAnswerRate", 0.02,
                        "falseAbstentionRate", 0.12
                ),
                Map.of(
                        "recallAt10", 0.02,
                        "mrr", 0.02,
                        "ndcgAt10", 0.02,
                        "contextRecall", 0.03,
                        "contextPrecision", 0.03
                ),
                Map.of(
                        "falseAnswerRate", 0.01,
                        "falseAbstentionRate", 0.02
                ),
                true
        );
    }

    private RagQualitySnapshot snapshot(RagQualityMetrics metrics) {
        return snapshot("corpus-v1", metrics);
    }

    private RagQualitySnapshot snapshot(
            String corpusVersion,
            RagQualityMetrics metrics
    ) {
        return new RagQualitySnapshot(
                "rag-benchmark-v1",
                corpusVersion,
                "deadbeef",
                "qwen3-embedding:4b/1024",
                "retrieval-v1",
                "learning-v1",
                "grounding-v1",
                "test-runtime",
                300,
                true,
                metrics,
                Map.of("en", metrics),
                Map.of("LEGAL", metrics),
                Map.of("FACTUAL", metrics),
                Instant.parse("2026-10-07T00:00:00Z")
        );
    }

    private RagQualityMetrics metrics(
            double recall10,
            double mrr,
            double ndcg10,
            double falseAnswerRate
    ) {
        return new RagQualityMetrics(
                Math.max(0.0, recall10 - 0.08),
                Math.max(0.0, recall10 - 0.02),
                recall10,
                mrr,
                ndcg10,
                0.86,
                0.78,
                0.72,
                0.92,
                0.88,
                falseAnswerRate,
                0.06
        );
    }
}
