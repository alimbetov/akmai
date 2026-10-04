package kz.alimbetov.akmai.runtimeconfig;

public class AppParameterConflictException extends RuntimeException {

    public AppParameterConflictException(
            String key,
            long expectedVersion
    ) {
        super(
                "App parameter "
                        + key
                        + " was modified concurrently; expected version "
                        + expectedVersion
        );
    }
}
