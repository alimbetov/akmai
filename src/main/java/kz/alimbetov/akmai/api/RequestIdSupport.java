package kz.alimbetov.akmai.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class RequestIdSupport {

    private RequestIdSupport() {
    }

    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute("akmai.requestId");
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String header = request.getHeader("X-Request-Id");
        String value = header == null || header.isBlank()
                ? UUID.randomUUID().toString()
                : sanitize(header);
        request.setAttribute("akmai.requestId", value);
        return value;
    }

    private static String sanitize(String value) {
        String clean = value.replaceAll("[\\r\\n\\t]+", "").trim();
        return clean.length() <= 100 ? clean : clean.substring(0, 100);
    }
}
