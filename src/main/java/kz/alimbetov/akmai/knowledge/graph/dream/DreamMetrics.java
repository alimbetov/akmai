package kz.alimbetov.akmai.knowledge.graph.dream;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Dream metrics with intentionally low-cardinality labels only. */
@Component
public class DreamMetrics {

    private final MeterRegistry registry;

    public DreamMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void run(String outcome, Duration duration) {
        registry.counter("akmai.adaptive.graph.dream.runs", "outcome", tag(outcome))
                .increment();
        registry.timer("akmai.adaptive.graph.dream.duration", "outcome", tag(outcome))
                .record(duration);
    }

    public void source(String lane) {
        registry.counter("akmai.adaptive.graph.dream.sources", "lane", tag(lane))
                .increment();
    }

    public void ann(String direction) {
        registry.counter("akmai.adaptive.graph.dream.ann.queries", "direction", tag(direction))
                .increment();
    }

    public void cache(boolean hit) {
        registry.counter(
                "akmai.adaptive.graph.dream.cache",
                "result", hit ? "hit" : "miss"
        ).increment();
    }

    public void mutual(boolean mutual) {
        registry.counter(
                "akmai.adaptive.graph.dream.mutual.knn",
                "result", mutual ? "mutual" : "one_way"
        ).increment();
    }

    public void candidate(String state) {
        registry.counter(
                "akmai.adaptive.graph.dream.candidates",
                "state", tag(state)
        ).increment();
    }

    public void budgetStop(DreamBudget.StopReason reason) {
        registry.counter(
                "akmai.adaptive.graph.dream.budget.stops",
                "reason", tag(reason.name())
        ).increment();
    }

    public void lease(String event) {
        registry.counter(
                "akmai.adaptive.graph.dream.lease.events",
                "event", tag(event)
        ).increment();
    }

    public void fencingRejected() {
        registry.counter("akmai.adaptive.graph.dream.fencing.rejections")
                .increment();
    }

    public void confidence(double value) {
        if (Double.isFinite(value) && value >= 0 && value <= 1) {
            registry.summary("akmai.adaptive.graph.dream.confidence")
                    .record(value);
        }
    }

    private String tag(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return "unknown";
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
