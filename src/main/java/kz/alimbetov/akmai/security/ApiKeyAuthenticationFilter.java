package kz.alimbetov.akmai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import kz.alimbetov.akmai.api.ApiErrorResponse;
import kz.alimbetov.akmai.api.RequestIdSupport;
import kz.alimbetov.akmai.config.SecurityProperties;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(
            SecurityProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RequestIdSupport.requestId(request);

        if (!properties.enabled()
                || !requiresApiKey(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String supplied = suppliedCredential(request);
        if (!matches(supplied, properties.apiKey())) {
            unauthorized(request, response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new ApiKeyPrincipal(
                                "akmai-api-key",
                                properties.accessLevels()
                        ),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_API"))
                )
        );
        filterChain.doFilter(request, response);
    }

    private String suppliedCredential(
            HttpServletRequest request
    ) {
        String apiKey = request.getHeader("X-AKMAI-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null
                && authorization.regionMatches(
                        true,
                        0,
                        "Bearer ",
                        0,
                        7
                )) {
            return authorization.substring(7).trim();
        }
        return null;
    }

    private boolean requiresApiKey(String requestUri) {
        if (requestUri == null) {
            return false;
        }
        return requestUri.startsWith("/api/")
                || requestUri.equals("/actuator/metrics")
                || requestUri.startsWith("/actuator/metrics/")
                || requestUri.equals("/actuator/info")
                || requestUri.startsWith("/actuator/info/")
                || requestUri.equals("/actuator/prometheus")
                || requestUri.startsWith("/actuator/prometheus/");
    }

    private boolean matches(String supplied, String configured) {
        if (supplied == null
                || configured == null
                || configured.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void unauthorized(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(
                        "UNAUTHORIZED",
                        "Valid X-AKMAI-API-Key is required",
                        RequestIdSupport.requestId(request),
                        HttpServletResponse.SC_UNAUTHORIZED,
                        java.util.Map.of()
                )
        );
    }
}
