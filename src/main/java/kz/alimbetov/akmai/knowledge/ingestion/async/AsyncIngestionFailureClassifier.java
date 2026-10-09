package kz.alimbetov.akmai.knowledge.ingestion.async;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.ingestion.PublicationOutcomeUnknownException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

@Component
public class AsyncIngestionFailureClassifier {

    public Failure classify(RuntimeException exception) {
        if (exception instanceof PublicationOutcomeUnknownException) {
            return new Failure(
                    Classification.AMBIGUOUS,
                    true,
                    null,
                    "PUBLICATION_OUTCOME_UNKNOWN"
            );
        }
        if (exception instanceof IdempotencyConflictException conflict) {
            return switch (conflict.code()) {
                case "INGESTION_IN_PROGRESS" -> new Failure(
                        Classification.RETRYABLE,
                        false,
                        conflict.retryAfterSeconds() == null
                                ? null
                                : Duration.ofSeconds(conflict.retryAfterSeconds()),
                        conflict.code()
                );
                case "INGESTION_IDEMPOTENCY_LOST" -> new Failure(
                        Classification.RETRYABLE,
                        true,
                        null,
                        conflict.code()
                );
                default -> new Failure(
                        Classification.NON_RETRYABLE,
                        true,
                        null,
                        conflict.code()
                );
            };
        }
        if (exception instanceof IllegalArgumentException) {
            return new Failure(
                    Classification.NON_RETRYABLE,
                    true,
                    null,
                    "VALIDATION_ERROR"
            );
        }

        HttpStatusCodeException http = findCause(
                exception,
                HttpStatusCodeException.class
        );
        if (http != null
                && (http.getStatusCode().value() == 429
                    || http.getStatusCode().is5xxServerError())) {
            return new Failure(
                    Classification.RETRYABLE,
                    true,
                    null,
                    http.getStatusCode().value() == 429
                            ? "DEPENDENCY_RATE_LIMITED"
                            : "DEPENDENCY_SERVER_ERROR"
            );
        }

        if (exception instanceof RejectedExecutionException
                || hasCause(exception, TransientDataAccessException.class)
                || hasCause(exception, ResourceAccessException.class)
                || hasCause(exception, SocketTimeoutException.class)
                || hasCause(exception, ConnectException.class)
                || hasCause(exception, IOException.class)) {
            return new Failure(
                    Classification.RETRYABLE,
                    true,
                    null,
                    "TRANSIENT_DEPENDENCY_FAILURE"
            );
        }
        return new Failure(
                Classification.NON_RETRYABLE,
                true,
                null,
                "UNCLASSIFIED_FAILURE"
        );
    }

    private boolean hasCause(Throwable throwable, Class<?> type) {
        return findCause(throwable, type) != null;
    }

    private <T> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    public enum Classification {
        RETRYABLE,
        NON_RETRYABLE,
        AMBIGUOUS
    }

    public record Failure(
            Classification classification,
            boolean consumesFailureBudget,
            Duration suggestedDelay,
            String code
    ) {
        public boolean retryable() {
            return classification != Classification.NON_RETRYABLE;
        }
    }
}
