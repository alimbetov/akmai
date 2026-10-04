package kz.alimbetov.akmai.security;

public class AccessLevelForbiddenException extends RuntimeException {

    public AccessLevelForbiddenException(long accessLevel) {
        super("Access to accessLevel " + accessLevel + " is not allowed");
    }
}
