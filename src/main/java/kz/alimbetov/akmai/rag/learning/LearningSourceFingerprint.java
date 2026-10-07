package kz.alimbetov.akmai.rag.learning;

import kz.alimbetov.akmai.security.ApiKeyPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Produces a privacy-preserving identity for the caller contributing learning
 * or rollout evidence. Raw principal/API-key names are never persisted.
 * Unauthenticated/local traffic deliberately collapses into one source so it
 * cannot manufacture independent-source support by itself.
 */
@Component
public class LearningSourceFingerprint {

    private static final String LOCAL_OR_UNKNOWN = "local-or-unknown";

    private final LearningPrivacyFingerprint fingerprint;

    public LearningSourceFingerprint(LearningPrivacyFingerprint fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String current() {
        return value(false);
    }

    public String currentOperational() {
        return value(true);
    }

    private String value(boolean operational) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        String raw = authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof ApiKeyPrincipal principal
                ? "source:" + principal.name()
                : "source:" + LOCAL_OR_UNKNOWN;
        return operational
                ? fingerprint.fingerprintOperational(raw)
                : fingerprint.fingerprint(raw);
    }
}
