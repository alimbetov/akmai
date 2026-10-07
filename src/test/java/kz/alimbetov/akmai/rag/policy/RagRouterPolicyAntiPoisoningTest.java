package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.rag.learning.RagLearningEventRepository;
import org.junit.jupiter.api.Test;

class RagRouterPolicyAntiPoisoningTest {

    @Test
    void oneSourceCannotManufactureIndependentEvidenceWithParaphrases() {
        RagRouterPolicyTrainer trainer = trainer(
                6,
                3,
                20,
                0.60
        );
        List<RagLearningEventRepository.RouterTrainingSample> samples =
                new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            samples.add(sample("q-" + index, "source-a", 100));
        }

        var candidate = trainer.synthesize(samples);

        assertThat(candidate.routes()).doesNotContainKey("GENERIC");
        assertThat(evidence(candidate).get("status"))
                .isEqualTo("INSUFFICIENT_DISTINCT_SOURCES");
        assertThat(evidence(candidate).get("distinctAttributedSources"))
                .isEqualTo(1);
    }

    @Test
    void sourceWindowCapBoundsBurstBeforeSupportIsCalculated() {
        RagRouterPolicyTrainer trainer = trainer(
                6,
                3,
                2,
                0.60
        );
        List<RagLearningEventRepository.RouterTrainingSample> samples =
                new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            samples.add(sample("a-" + index, "source-a", 100));
        }
        for (int index = 0; index < 2; index++) {
            samples.add(sample("b-" + index, "source-b", 100));
            samples.add(sample("c-" + index, "source-c", 100));
        }

        var candidate = trainer.synthesize(samples);

        assertThat(candidate.routes()).containsKey("GENERIC");
        assertThat(evidence(candidate).get("admittedDistinctQueries"))
                .isEqualTo(6);
        assertThat(evidence(candidate).get("droppedBySourceWindowCap"))
                .isEqualTo(8);
        assertThat((double) evidence(candidate).get("maxSourceShare"))
                .isLessThanOrEqualTo(0.34);
    }

    @Test
    void concentratedEvidenceIsRejectedEvenWithThreeSources() {
        RagRouterPolicyTrainer trainer = trainer(
                6,
                3,
                20,
                0.50
        );
        List<RagLearningEventRepository.RouterTrainingSample> samples = List.of(
                sample("a-1", "source-a", 100),
                sample("a-2", "source-a", 100),
                sample("a-3", "source-a", 100),
                sample("a-4", "source-a", 100),
                sample("b-1", "source-b", 100),
                sample("c-1", "source-c", 2_000)
        );

        var candidate = trainer.synthesize(samples);

        assertThat(candidate.routes()).doesNotContainKey("GENERIC");
        assertThat(evidence(candidate).get("status"))
                .isEqualTo("SOURCE_CONCENTRATION_EXCEEDED");
        assertThat((double) evidence(candidate).get("latencyOutlierRate"))
                .isGreaterThan(0.0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evidence(RagRouterPolicyTrainer.Candidate candidate) {
        return (Map<String, Object>) candidate.evidence().get("GENERIC");
    }

    private RagRouterPolicyTrainer trainer(
            int minQueries,
            int minSources,
            int maxPerWindow,
            double maxSourceShare
    ) {
        return new RagRouterPolicyTrainer(
                null,
                null,
                new RouterLearningProperties(
                        true,
                        Duration.ofDays(30),
                        20_000,
                        minQueries,
                        0.03,
                        0.10,
                        Duration.ofDays(1),
                        maxPerWindow,
                        minSources,
                        maxSourceShare
                )
        );
    }

    private RagLearningEventRepository.RouterTrainingSample sample(
            String fingerprint,
            String source,
            long latency
    ) {
        return new RagLearningEventRepository.RouterTrainingSample(
                fingerprint,
                "GENERIC",
                source,
                Map.of("VECTOR", 1, "REFERENCE", 1),
                Map.of("VECTOR", 1, "REFERENCE", 1),
                latency,
                Instant.parse("2026-10-07T00:00:00Z")
        );
    }
}
