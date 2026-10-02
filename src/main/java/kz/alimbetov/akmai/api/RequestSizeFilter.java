package kz.alimbetov.akmai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import kz.alimbetov.akmai.config.ApiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestSizeFilter extends OncePerRequestFilter {

    private final ApiProperties properties;
    private final ObjectMapper objectMapper;

    public RequestSizeFilter(
            ApiProperties properties,
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
        long contentLength = request.getContentLengthLong();
        long maximum = maximumBodyBytes(request);
        if (contentLength > maximum && contentLength >= 0) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    new ApiErrorResponse(
                            "REQUEST_BODY_TOO_LARGE",
                            "Request body exceeds configured maximum",
                            RequestIdSupport.requestId(request),
                            HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                            Map.of("maxBytes", maximum)
                    )
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private long maximumBodyBytes(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/knowledge")) {
            return Math.addExact(
                    Math.multiplyExact(
                            (long) properties.maxDocumentChars(),
                            4L
                    ),
                    properties.maxMetadataBytes()
            ) + 64_000L;
        }
        if (request.getRequestURI().startsWith("/api/rag")) {
            return Math.multiplyExact(
                    (long) properties.maxQuestionChars(),
                    4L
            ) + 16_000L;
        }
        return Long.MAX_VALUE;
    }
}
