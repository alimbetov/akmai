package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void blankTextHasZeroTokens() {
        assertThat(estimator.estimate("   \n\t")).isZero();
    }

    @Test
    void hanTextUsesMoreConservativeBudgetThanLatinText() {
        String latin = "a".repeat(300);
        String han = "法".repeat(300);

        assertThat(estimator.estimate(han))
                .isGreaterThan(estimator.estimate(latin));
        assertThat(estimator.estimate(han)).isGreaterThanOrEqualTo(300);
    }

    @Test
    void cyrillicIsConservativeWithoutTreatingEveryCharacterAsToken() {
        String cyrillic = "а".repeat(300);
        int estimate = estimator.estimate(cyrillic);

        assertThat(estimate).isGreaterThan(100);
        assertThat(estimate).isLessThan(300);
    }

    @Test
    void supplementarySymbolsAreNotUndercountedAsPlainLatin() {
        String emoji = "🙂".repeat(100);

        assertThat(estimator.estimate(emoji)).isGreaterThanOrEqualTo(100);
    }
}
