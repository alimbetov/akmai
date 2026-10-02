package kz.alimbetov.akmai.rag.access;

import java.util.Set;
import java.util.TreeSet;
import kz.alimbetov.akmai.config.SecurityProperties;
import kz.alimbetov.akmai.rag.api.QuestionRequest;
import kz.alimbetov.akmai.security.ApiKeyPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class RequestAccessLevelResolver implements AccessLevelResolver {

    private final SecurityProperties securityProperties;

    public RequestAccessLevelResolver(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public Set<Long> resolve(QuestionRequest request) {
        Set<Long> requested = requestedLevels(request);
        if (requested.isEmpty()) {
            return Set.of();
        }

        Set<Long> allowed = allowedLevels();
        if (allowed.isEmpty()) {
            return Set.of();
        }

        TreeSet<Long> effective = new TreeSet<>(requested);
        effective.retainAll(allowed);
        return Set.copyOf(effective);
    }

    private Set<Long> requestedLevels(QuestionRequest request) {
        if (request == null
                || request.accessLevels() == null
                || request.accessLevels().isEmpty()) {
            return Set.of();
        }

        TreeSet<Long> normalized = new TreeSet<>();
        for (Long value : request.accessLevels()) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(
                        "accessLevels must contain only positive values"
                );
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private Set<Long> allowedLevels() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof ApiKeyPrincipal principal) {
            return principal.accessLevels();
        }
        if (!securityProperties.enabled()
                && securityProperties.allowUnauthenticatedLocal()) {
            return securityProperties.accessLevels();
        }
        return Set.of();
    }
}
