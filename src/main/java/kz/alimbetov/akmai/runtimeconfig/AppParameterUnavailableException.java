package kz.alimbetov.akmai.runtimeconfig;

public class AppParameterUnavailableException extends RuntimeException {

    public AppParameterUnavailableException(Throwable cause) {
        super("Runtime parameter store is temporarily unavailable", cause);
    }
}
