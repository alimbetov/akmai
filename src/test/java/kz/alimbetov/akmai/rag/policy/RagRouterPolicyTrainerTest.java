package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.rag.learning.RagLearningEventRepository;
import org.junit.jupiter.api.Test;

class RagRouterPolicyTrainerTest {

    @Test
    void removesOnlyUnsupportedOptionalLaneAfterEnoughIndependentEvidence() {
        RagRouterPolicyTrainer trainer = new RagRouterPolicyTrainer(
                null,
                null,
                properties(10)
        );
        List<RagLearningEventRepository.RouterTrainingSample> samples =
                new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            samples.add(sample(
                    "fp-" + index,
                    "GENERIC",
                    Map.of("VECTOR", 1, "REFERENCE", 1),
                    Map.of("VECTOR", 1, "REFERENCE", 1)
            ));
        }

        var candidate = trainer.synthesize(samples);

        assertThat(candidate.routes().get("GENERIC"))
                .containsExactly("REFERENCE", "VECTOR");
        assertThat(candidate.evidence().get("GENERIC")).isNotNull();
    }

    @Test
    void keepsOptionalLaneWhenItContributesOftenEnough() {
        RagRouterPolicyTrainer trainer = new RagRouterPolicyTrainer(
                null,
                null,
                properties(10)
        );
        List<RagLearningEventRepository.RouterTrainingSample> samples =
                new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            boolean lexicalUseful = index < 2;
            samples.add(sample(
                    "fp-" + index,
                    "GENERIC",
                    lexicalUseful
                            ? Map.of("VECTOR", 1, "REFERENCE", 1, "LEXICAL", 1)
                            : Map.of("VECTOR", 1, "REFERENCE", 1),
                    lexicalUseful
                            ? Map.of("VECTOR", 1, "REFERENCE", 1, "LEXICAL", 1)
                            : Map.of("VECTOR", 1, "REFERENCE", 1)
            ));
        }

        var candidate = trainer.synthesize(samples);

        assertThat(candidate.routes().get("GENERIC"))
                .containsExactly("LEXICAL", "REFERENCE", "VECTOR");
    }

    @Test
    void doesNotCreateRouteWithoutMinimumIndependentSupport() {
        RagRouterPolicyTrainer trainer = new RagRouterPolicyTrainer(
                null,
                null,
                properties(10)
        );
        List<RagLearningEventRepository.RouterTrainingSample> samples =
                java.util.stream.IntStream.range(0, 9)
                        .mapToObj(index -> sample(
                                "fp-" + index,
                                "GENERIC",
                                Map.of("VECTOR", 1),
                                Map.of("VECTOR", 1)
                        ))
                        .toList();

        var candidate = trainer.synthesize(samples);

        assertThat(candidate.routes()).doesNotContainKey("GENERIC");
    }

    private RouterLearningProperties properties(int minimum) {
        return new RouterLearningProperties(
                true,
                Duration.ofDays(30),
                20_000,
                minimum,
                0.03,
                0.10
        );
    }

    private RagLearningEventRepository.RouterTrainingSample sample(
            String fingerprint,
            String queryClass,
            Map<String, Integer> selected,
            Map<String, Integer> cited
    ) {
        return new RagLearningEventRepository.RouterTrainingSample(
                fingerprint,
                queryClass,
                selected,
                cited,
                100,
                Instant.parse("2026-10-07T00:00:00Z")
        );
    }
}
