package kz.alimbetov.akmai.knowledge.idempotency;

public class IdempotencyConflictException extends RuntimeException {

    private final String code;
    private final Long retryAfterSeconds;

    public IdempotencyConflictException(String code, String message) {
        this(code, message, null);
    }

    public IdempotencyConflictException(
            String code,
            String message,
            Long retryAfterSeconds
    ) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
