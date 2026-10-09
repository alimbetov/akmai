package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.assertj.core.api.Assertions.assertThat;

import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.ingestion.PublicationOutcomeUnknownException;
import org.junit.jupiter.api.Test;

class AsyncIngestionFailureClassifierTest {

    private final AsyncIngestionFailureClassifier classifier =
            new AsyncIngestionFailureClassifier();

    @Test
    void inProgressIsRetryableWithoutConsumingFailureBudget() {
        var result = classifier.classify(new IdempotencyConflictException(
                "INGESTION_IN_PROGRESS",
                "still running",
                17L
        ));

        assertThat(result.classification())
                .isEqualTo(AsyncIngestionFailureClassifier.Classification.RETRYABLE);
        assertThat(result.consumesFailureBudget()).isFalse();
        assertThat(result.suggestedDelay()).hasSeconds(17L);
        assertThat(result.code()).isEqualTo("INGESTION_IN_PROGRESS");
    }

    @Test
    void lostIngestionClaimIsRetryableRecovery() {
        var result = classifier.classify(new IdempotencyConflictException(
                "INGESTION_IDEMPOTENCY_LOST",
                "claim lost"
        ));

        assertThat(result.classification())
                .isEqualTo(AsyncIngestionFailureClassifier.Classification.RETRYABLE);
        assertThat(result.consumesFailureBudget()).isTrue();
    }

    @Test
    void publicationOutcomeUnknownIsAmbiguousAndRetryable() {
        var result = classifier.classify(new PublicationOutcomeUnknownException(
                "unknown",
                new IllegalStateException("resolver unavailable")
        ));

        assertThat(result.classification())
                .isEqualTo(AsyncIngestionFailureClassifier.Classification.AMBIGUOUS);
        assertThat(result.retryable()).isTrue();
        assertThat(result.consumesFailureBudget()).isTrue();
        assertThat(result.code()).isEqualTo("PUBLICATION_OUTCOME_UNKNOWN");
    }

    @Test
    void deterministicValidationIsTerminal() {
        var result = classifier.classify(new IllegalArgumentException(
                "invalid canonical document"
        ));

        assertThat(result.classification())
                .isEqualTo(AsyncIngestionFailureClassifier.Classification.NON_RETRYABLE);
        assertThat(result.retryable()).isFalse();
        assertThat(result.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void keyReuseIsTerminal() {
        var result = classifier.classify(new IdempotencyConflictException(
                "IDEMPOTENCY_KEY_REUSE",
                "different fingerprint"
        ));

        assertThat(result.classification())
                .isEqualTo(AsyncIngestionFailureClassifier.Classification.NON_RETRYABLE);
        assertThat(result.retryable()).isFalse();
    }
}
