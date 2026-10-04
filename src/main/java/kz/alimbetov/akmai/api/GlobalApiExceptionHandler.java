package kz.alimbetov.akmai.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.security.AccessLevelForbiddenException;
import kz.alimbetov.akmai.runtimeconfig.AppParameterConflictException;
import kz.alimbetov.akmai.rag.service.AnswerGenerationException;
import kz.alimbetov.akmai.rag.service.RetrievalUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    @ExceptionHandler({
        ApiValidationException.class,
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> validation(
            Exception exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                safeValidationMessage(exception),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(AccessLevelForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> accessDenied(
            AccessLevelForbiddenException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.FORBIDDEN,
                "ACCESS_LEVEL_FORBIDDEN",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(AppParameterConflictException.class)
    public ResponseEntity<ApiErrorResponse> appParameterConflict(
            AppParameterConflictException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                "APP_PARAMETER_CONFLICT",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> idempotency(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        ResponseEntity<ApiErrorResponse> result = response(
                HttpStatus.CONFLICT,
                exception.code(),
                exception.getMessage(),
                request,
                Map.of()
        );
        if (exception.retryAfterSeconds() == null) {
            return result;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(
                        "Retry-After",
                        Long.toString(exception.retryAfterSeconds())
                )
                .body(result.getBody());
    }

    @ExceptionHandler({
        RetrievalUnavailableException.class,
        AnswerGenerationException.class
    })
    public ResponseEntity<ApiErrorResponse> dependencyUnavailable(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "KNOWLEDGE_BACKEND_UNAVAILABLE",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<ApiErrorResponse> overloaded(
            RejectedExecutionException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_OVERLOADED",
                "Service is temporarily overloaded",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> internal(
            Exception exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal server error",
                request,
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, Object> details
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                code,
                message,
                RequestIdSupport.requestId(request),
                status.value(),
                details
        ));
    }

    private String safeValidationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException validation) {
            return validation.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getField() + " " + error.getDefaultMessage())
                    .orElse("Request validation failed");
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return "Malformed request body";
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Request validation failed";
        }
        String clean = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }
}
