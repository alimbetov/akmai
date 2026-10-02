package kz.alimbetov.akmai.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.alimbetov.akmai.config.SecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityApiIntegrationTest {

    private final ApiKeyAuthenticationFilter filter =
            new ApiKeyAuthenticationFilter(
                    new SecurityProperties(
                            true,
                            "secret-key",
                            false,
                            java.util.Set.of(1L, 2L)
                    ),
                    new ObjectMapper()
            );

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingApiKeyWithStable401Contract() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/rag/ask"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("X-AKMAI-API-Key");
    }

    @Test
    void authenticatesValidApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/knowledge/text"
        );
        request.addHeader("X-AKMAI-API-Key", "secret-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()
                .isAuthenticated()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal())
                .isEqualTo(new ApiKeyPrincipal(
                        "akmai-api-key",
                        java.util.Set.of(1L, 2L)
                ));
    }
}
