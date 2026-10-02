package kz.alimbetov.akmai.rag.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import kz.alimbetov.akmai.config.SecurityProperties;
import kz.alimbetov.akmai.rag.api.QuestionRequest;
import kz.alimbetov.akmai.security.ApiKeyPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RequestAccessLevelResolverTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void intersectsRequestedLevelsWithAuthenticatedPrincipal() {
        RequestAccessLevelResolver resolver = resolver(
                true,
                false,
                Set.of(1L, 2L, 3L)
        );
        authenticate(Set.of(1L, 3L));

        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                Set.of(1L, 2L, 3L, 99L)
        ))).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void requestCannotWidenAuthenticatedScope() {
        RequestAccessLevelResolver resolver = resolver(
                true,
                false,
                Set.of(1L, 2L, 3L)
        );
        authenticate(Set.of(2L));

        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                Set.of(1L, 2L, 3L)
        ))).containsExactly(2L);
    }

    @Test
    void unauthenticatedLocalModeUsesServerConfiguredScope() {
        RequestAccessLevelResolver resolver = resolver(
                false,
                true,
                Set.of(1L, 2L)
        );

        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                Set.of(2L, 3L)
        ))).containsExactly(2L);
    }

    @Test
    void securedUnauthenticatedRequestFailsClosed() {
        RequestAccessLevelResolver resolver = resolver(
                true,
                false,
                Set.of(1L, 2L)
        );

        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                Set.of(1L)
        ))).isEmpty();
    }

    @Test
    void emptyScopeFailsClosed() {
        RequestAccessLevelResolver resolver = resolver(
                false,
                true,
                Set.of(1L, 2L)
        );

        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                Set.of()
        ))).isEmpty();
        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                null
        ))).isEmpty();
    }

    @Test
    void directInvalidScopeIsRejected() {
        RequestAccessLevelResolver resolver = resolver(
                false,
                true,
                Set.of(1L)
        );
        HashSet<Long> values = new HashSet<>();
        values.add(1L);
        values.add(0L);

        assertThatThrownBy(() -> resolver.resolve(
                new QuestionRequest("question", values)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private RequestAccessLevelResolver resolver(
            boolean enabled,
            boolean local,
            Set<Long> levels
    ) {
        return new RequestAccessLevelResolver(
                new SecurityProperties(enabled, "secret-key", local, levels)
        );
    }

    private void authenticate(Set<Long> levels) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new ApiKeyPrincipal("test", levels),
                        null,
                        java.util.List.of()
                )
        );
    }
}
