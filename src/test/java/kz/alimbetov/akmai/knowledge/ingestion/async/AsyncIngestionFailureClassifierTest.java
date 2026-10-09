package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.ingestion.PublicationOutcomeUnknownException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

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

    @Test
    void dependencyRateLimitIsRetryable() {
        RuntimeException error = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate limited",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );

        var result = classifier.classify(error);

        assertThat(result.classification())
                .isEqualTo(AsyncIngestionFailureClassifier.Classification.RETRYABLE);
        assertThat(result.code()).isEqualTo("DEPENDENCY_RATE_LIMITED");
    }

    @Test
    void dependencyServerFailureIsRetryable() {
        RuntimeException error = HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "temporarily unavailable",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );

        var result = classifier.classify(error);

        assertThat(result.classification())
                .isEqualTo(AsyncIngestionFailureClassifier.Classification.RETRYABLE);
        assertThat(result.code()).isEqualTo("DEPENDENCY_SERVER_ERROR");
    }
}
