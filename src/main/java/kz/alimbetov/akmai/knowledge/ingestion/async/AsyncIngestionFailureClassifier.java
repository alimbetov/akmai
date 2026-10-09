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
        if (exception instanceof RejectedExecutionException
                || hasCause(exception, TransientDataAccessException.class)
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
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
