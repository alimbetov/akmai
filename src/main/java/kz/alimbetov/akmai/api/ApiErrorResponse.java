package kz.alimbetov.akmai.api;

import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        int status,
        Map<String, Object> details
) {
    public ApiErrorResponse {
        details = Map.copyOf(details == null ? Map.of() : details);
    }
}
