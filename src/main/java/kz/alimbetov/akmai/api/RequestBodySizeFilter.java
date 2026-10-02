package kz.alimbetov.akmai.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kz.alimbetov.akmai.config.ApiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestBodySizeFilter extends OncePerRequestFilter {

    private final ApiProperties properties;

    public RequestBodySizeFilter(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !("POST".equals(method)
                || "PUT".equals(method)
                || "PATCH".equals(method));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > properties.maxRequestBytes()) {
            response.sendError(
                    HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "Request body exceeds configured maximum"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }
}
