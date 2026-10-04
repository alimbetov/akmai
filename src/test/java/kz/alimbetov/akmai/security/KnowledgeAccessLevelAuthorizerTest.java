package kz.alimbetov.akmai.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import kz.alimbetov.akmai.config.SecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class KnowledgeAccessLevelAuthorizerTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedPrincipalMayWriteOnlyItsAccessLevels() {
        KnowledgeAccessLevelAuthorizer authorizer =
                new KnowledgeAccessLevelAuthorizer(
                        new SecurityProperties(
                                true,
                                "secret",
                                false,
                                Set.of(1L, 2L, 3L)
                        )
                );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new ApiKeyPrincipal(
                                "test",
                                Set.of(2L)
                        ),
                        null,
                        java.util.List.of()
                )
        );

        authorizer.requireWriteAccess(2L);

        assertThatThrownBy(() -> authorizer.requireWriteAccess(1L))
                .isInstanceOf(AccessLevelForbiddenException.class)
                .hasMessageContaining("accessLevel 1");
    }

    @Test
    void localUnauthenticatedModeUsesConfiguredScope() {
        KnowledgeAccessLevelAuthorizer authorizer =
                new KnowledgeAccessLevelAuthorizer(
                        new SecurityProperties(
                                false,
                                "",
                                true,
                                Set.of(3L)
                        )
                );

        authorizer.requireWriteAccess(3L);

        assertThatThrownBy(() -> authorizer.requireWriteAccess(4L))
                .isInstanceOf(AccessLevelForbiddenException.class);
    }

    @Test
    void securedRequestWithoutPrincipalFailsClosed() {
        KnowledgeAccessLevelAuthorizer authorizer =
                new KnowledgeAccessLevelAuthorizer(
                        new SecurityProperties(
                                true,
                                "secret",
                                false,
                                Set.of(1L)
                        )
                );

        assertThatThrownBy(() -> authorizer.requireWriteAccess(1L))
                .isInstanceOf(AccessLevelForbiddenException.class);
    }
}
