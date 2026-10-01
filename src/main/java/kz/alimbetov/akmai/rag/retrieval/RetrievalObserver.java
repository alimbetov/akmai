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
