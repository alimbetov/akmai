package kz.alimbetov.akmai.runtimeconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.security.ApiKeyPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AppParameterControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsAuthoritativeParameters() {
        AppParameterService service = mock(AppParameterService.class);
        ResolvedAppParameter parameter = new ResolvedAppParameter(
                AppParameterKey.ADAPTIVE_GRAPH_LEARNING_ENABLED,
                false,
                0L,
                Instant.parse("2026-10-04T08:00:00Z"),
                "liquibase",
                AppParameterSource.DATABASE
        );
        when(service.listAuthoritative())
                .thenReturn(List.of(parameter));

        AppParameterController controller =
                new AppParameterController(service);

        assertThat(controller.list())
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.key()).isEqualTo(
                            "akmai.adaptive-graph.learning-enabled"
                    );
                    assertThat(response.value()).isFalse();
                    assertThat(response.version()).isZero();
                    assertThat(response.source()).isEqualTo("DATABASE");
                });
    }

    @Test
    void updateUsesAuthenticatedAdminAsAuditActor() {
        AppParameterService service = mock(AppParameterService.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new ApiKeyPrincipal(
                                "akmai-admin-key",
                                Set.of(1L)
                        ),
                        null,
                        List.of()
                )
        );
        ResolvedAppParameter updated = new ResolvedAppParameter(
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED,
                true,
                1L,
                Instant.parse("2026-10-04T08:00:00Z"),
                "akmai-admin-key",
                AppParameterSource.DATABASE
        );
        when(service.updateBoolean(
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED,
                true,
                0L,
                "akmai-admin-key"
        )).thenReturn(updated);

        AppParameterController controller =
                new AppParameterController(service);
        AppParameterResponse response = controller.update(
                "akmai.adaptive-graph.shadow-expansion-enabled",
                new AppParameterUpdateRequest(true, 0L)
        );

        assertThat(response.value()).isTrue();
        verify(service).updateBoolean(
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED,
                true,
                0L,
                "akmai-admin-key"
        );
    }
}
