package kz.alimbetov.akmai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import kz.alimbetov.akmai.rag.retrieval.RetrievalObserver;
import kz.alimbetov.akmai.rag.retrieval.RetrievalOutcomeStatus;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class PrometheusTelemetryContractTest {

    @Test
    void exportsOperationalSeriesUsedByAlertRules() throws Exception {
        PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AkmaiMetrics metrics = new AkmaiMetrics(registry);
        RetrievalObserver retrieval = new RetrievalObserver(registry);

        metrics.retentionBacklog(42);
        retrieval.outcome(
                RetrievalType.VECTOR,
                RetrievalOutcomeStatus.TIMED_OUT,
                "TIMEOUT"
        );

        var executor = BoundedExecutorFactory.createMonitored(
                1,
                1,
                registry,
                "retrieval"
        );
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> await(release));
            assertThatThrownBy(() ->
                    executor.execute(() -> {
                    })
            ).isInstanceOf(RejectedExecutionException.class);

            String scrape = registry.scrape();
            assertThat(scrape)
                    .contains("akmai_retention_backlog")
                    .contains("akmai_retrieval_outcomes_total")
                    .contains("status=\"TIMED_OUT\"")
                    .contains("akmai_executor_rejected_total")
                    .contains("role=\"retrieval\"");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
