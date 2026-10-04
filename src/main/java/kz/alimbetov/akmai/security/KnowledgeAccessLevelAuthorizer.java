package kz.alimbetov.akmai.security;

import kz.alimbetov.akmai.config.SecurityProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeAccessLevelAuthorizer {

    private final SecurityProperties properties;

    public KnowledgeAccessLevelAuthorizer(SecurityProperties properties) {
        this.properties = properties;
    }

    public void requireWriteAccess(long accessLevel) {
        if (accessLevel <= 0) {
            throw new IllegalArgumentException(
                    "accessLevel must be positive"
            );
        }
        if (!allowed(accessLevel)) {
            throw new AccessLevelForbiddenException(accessLevel);
        }
    }

    private boolean allowed(long accessLevel) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal()
                instanceof ApiKeyPrincipal principal) {
            return principal.accessLevels().contains(accessLevel);
        }

        return !properties.enabled()
                && properties.allowUnauthenticatedLocal()
                && properties.accessLevels().contains(accessLevel);
    }
}
