package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetrievalObserverTest {

    @Test
    void distinguishesSuccessfulZeroHitFromStrategyFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalObserver observer = new RetrievalObserver(registry);

        observer.success(RetrievalType.LEXICAL, Duration.ofMillis(5), 0);
        observer.failure(
                RetrievalType.VECTOR,
                Duration.ofMillis(7),
                new IllegalStateException("down")
        );

        assertThat(registry.get("akmai.retrieval.strategy")
                .tag("strategy", "LEXICAL")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get("akmai.retrieval.failures")
                .tag("strategy", "VECTOR")
                .tag("exception", "IllegalStateException")
                .counter()
                .count()).isEqualTo(1);
    }
}
