package kz.alimbetov.akmai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ReembeddingFailurePreservationTest {

    @Test
    void cleanupFailureIsSuppressedOnPrimaryFailure() {
        IllegalStateException primary =
                new IllegalStateException("embedding failed");
        IllegalArgumentException cleanup =
                new IllegalArgumentException("abort failed");

        boolean result = ReembeddingService.preservePrimaryFailure(
                primary,
                () -> {
                    throw cleanup;
                },
                false
        );

        assertThat(result).isFalse();
        assertThat(primary.getSuppressed()).containsExactly(cleanup);
        assertThat(primary.getMessage()).isEqualTo("embedding failed");
    }

    @Test
    void successfulCleanupReturnsItsResultWithoutAddingSuppressedFailures() {
        IllegalStateException primary =
                new IllegalStateException("embedding failed");

        boolean result = ReembeddingService.preservePrimaryFailure(
                primary,
                () -> true,
                false
        );

        assertThat(result).isTrue();
        assertThat(primary.getSuppressed()).isEmpty();
    }

    @Test
    void cleanupExecutesExactlyOnce() {
        IllegalStateException primary =
                new IllegalStateException("embedding failed");
        AtomicBoolean executed = new AtomicBoolean();

        int result = ReembeddingService.preservePrimaryFailure(
                primary,
                () -> {
                    executed.set(true);
                    return 7;
                },
                0
        );

        assertThat(executed).isTrue();
        assertThat(result).isEqualTo(7);
    }

    @Test
    void primaryIsNotAddedAsItsOwnSuppressedException() {
        IllegalStateException primary =
                new IllegalStateException("embedding failed");

        int result = ReembeddingService.preservePrimaryFailure(
                primary,
                () -> {
                    throw primary;
                },
                0
        );

        assertThat(result).isZero();
        assertThat(primary.getSuppressed()).isEmpty();
    }
}
