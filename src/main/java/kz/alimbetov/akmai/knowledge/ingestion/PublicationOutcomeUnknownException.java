package kz.alimbetov.akmai.knowledge.ingestion;

public class PublicationOutcomeUnknownException extends RuntimeException {

    public PublicationOutcomeUnknownException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
