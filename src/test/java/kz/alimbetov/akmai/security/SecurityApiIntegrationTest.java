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

    private static final String API_KEY =
            API_KEY;
    private static final String ADMIN_KEY =
            "abcdef0123456789abcdef0123456789";

    private final ApiKeyAuthenticationFilter filter =
            new ApiKeyAuthenticationFilter(
                    new SecurityProperties(
                            true,
                            API_KEY,
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
    void rejectsMissingApiKeyForSensitiveManagementEndpoint()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/actuator/metrics/jvm.memory.used"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void healthProbeRemainsUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/actuator/health/readiness"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    void authenticatesValidApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/knowledge/text"
        );
        request.addHeader("X-AKMAI-API-Key", API_KEY);
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

    @Test
    void rejectsNormalApiKeyForAdminEndpoint() throws Exception {
        ApiKeyAuthenticationFilter adminFilter =
                new ApiKeyAuthenticationFilter(
                        new SecurityProperties(
                                true,
                                API_KEY,
                                ADMIN_KEY,
                                false,
                                java.util.Set.of(1L, 2L)
                        ),
                        new ObjectMapper()
                );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/admin/app-parameters"
        );
        request.addHeader("X-AKMAI-API-Key", API_KEY);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        adminFilter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("X-AKMAI-Admin-Key");
    }

    @Test
    void authenticatesDedicatedAdminKeyForAdminEndpoint()
            throws Exception {
        ApiKeyAuthenticationFilter adminFilter =
                new ApiKeyAuthenticationFilter(
                        new SecurityProperties(
                                true,
                                API_KEY,
                                ADMIN_KEY,
                                false,
                                java.util.Set.of(1L, 2L)
                        ),
                        new ObjectMapper()
                );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/admin/app-parameters"
        );
        request.addHeader("X-AKMAI-Admin-Key", ADMIN_KEY);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        adminFilter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_ADMIN");
        assertThat(SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal())
                .isEqualTo(new ApiKeyPrincipal(
                        "akmai-admin-key",
                        java.util.Set.of(1L, 2L)
                ));
    }

    @Test
    void authenticatesPrometheusScrapeWithBearerCredential()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/actuator/prometheus"
        );
        request.addHeader(
                "Authorization",
                "Bearer 0123456789abcdef0123456789abcdef"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
    }

    @Test
    void authenticatesValidApiKeyForMetrics() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/actuator/metrics"
        );
        request.addHeader(
                "X-AKMAI-API-Key",
                API_KEY
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
    }
}
