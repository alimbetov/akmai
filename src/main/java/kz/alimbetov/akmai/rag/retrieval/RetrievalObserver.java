package kz.alimbetov.akmai.rag.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RetrievalObserver {

    private final MeterRegistry meterRegistry;

    public RetrievalObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void success(RetrievalType type, Duration duration, int hits) {
        timer(type, "success").record(duration);
        meterRegistry.counter(
                "akmai.retrieval.hits",
                "strategy", type.name()
        ).increment(hits);
    }

    public void failure(RetrievalType type, Duration duration, Throwable error) {
        timer(type, "failure").record(duration);
        meterRegistry.counter(
                "akmai.retrieval.failures",
                "strategy", type.name(),
                "exception", error.getClass().getSimpleName()
        ).increment();
    }

    public void outcome(
            RetrievalType type,
            RetrievalOutcomeStatus status,
            String category
    ) {
        meterRegistry.counter(
                "akmai.retrieval.outcomes",
                "strategy", type.name(),
                "status", status.name(),
                "category", category == null ? "NONE" : category
        ).increment();
    }

    public void attribution(
            RetrievalAttributionStage stage,
            RetrievalType type,
            int count
    ) {
        if (stage == null || type == null || count <= 0) {
            return;
        }
        meterRegistry.counter(
                "akmai.retrieval.attribution",
                "stage", stage.name(),
                "strategy", type.name()
        ).increment(count);
    }

    public void attributionRequest(
            RetrievalAttributionStage stage,
            RetrievalType type
    ) {
        if (stage == null || type == null) {
            return;
        }
        meterRegistry.counter(
                "akmai.retrieval.attribution.requests",
                "stage", stage.name(),
                "strategy", type.name()
        ).increment();
    }

    public void plannerLane(
            String mode,
            RetrievalType type,
            int count
    ) {
        if (type == null || count <= 0) {
            return;
        }
        meterRegistry.counter(
                "akmai.retrieval.planner.lanes",
                "mode", plannerMode(mode),
                "strategy", type.name()
        ).increment(count);
    }

    public void plannerDelta(
            String action,
            RetrievalType type,
            int count
    ) {
        if (type == null || count <= 0) {
            return;
        }
        meterRegistry.counter(
                "akmai.retrieval.planner.delta",
                "action", plannerAction(action),
                "strategy", type.name()
        ).increment(count);
    }

    public void plannerQueryClass(String queryClass) {
        String safe = boundedIdentifier(queryClass, "UNKNOWN");
        meterRegistry.counter(
                "akmai.retrieval.planner.queries",
                "class", safe
        ).increment();
    }

    public void evidenceQuality(String quality) {
        meterRegistry.counter(
                "akmai.retrieval.evidence.quality",
                "quality", boundedIdentifier(quality, "UNKNOWN")
        ).increment();
    }

    public void rerankSuccess(Duration duration, int candidates) {
        Timer.builder("akmai.retrieval.reranker")
                .tag("outcome", "success")
                .register(meterRegistry)
                .record(duration);
        meterRegistry.summary("akmai.retrieval.reranker.candidates").record(candidates);
    }

    public void rerankFailure(Duration duration, Throwable error) {
        Timer.builder("akmai.retrieval.reranker")
                .tag("outcome", "failure")
                .register(meterRegistry)
                .record(duration);
        meterRegistry.counter(
                "akmai.retrieval.reranker.failures",
                "exception", rootCause(error).getClass().getSimpleName()
        ).increment();
    }

    private String plannerMode(String value) {
        return switch (value == null ? "" : value) {
            case "current" -> "current";
            case "shadow" -> "shadow";
            default -> "unknown";
        };
    }

    private String plannerAction(String value) {
        return switch (value == null ? "" : value) {
            case "added" -> "added";
            case "omitted" -> "omitted";
            default -> "unknown";
        };
    }

    private String boundedIdentifier(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() > 48
                || !normalized.matches("[A-Z0-9_]+")) {
            return fallback;
        }
        return normalized;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private Timer timer(RetrievalType type, String outcome) {
        return Timer.builder("akmai.retrieval.strategy")
                .tag("strategy", type.name())
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
