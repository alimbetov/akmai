package kz.alimbetov.akmai.api;

public class ApiValidationException extends RuntimeException {

    public ApiValidationException(String message) {
        super(message);
    }
}
