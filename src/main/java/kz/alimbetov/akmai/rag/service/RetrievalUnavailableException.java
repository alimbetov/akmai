package kz.alimbetov.akmai.rag.service;

public class RetrievalUnavailableException extends RuntimeException {

    public RetrievalUnavailableException(String message) {
        super(message);
    }

    public RetrievalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
