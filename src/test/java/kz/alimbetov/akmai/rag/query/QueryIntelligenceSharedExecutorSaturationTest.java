package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.rag.grounding.OllamaSemanticEntailmentClient;
import kz.alimbetov.akmai.rag.grounding.SemanticEntailmentClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class QueryIntelligenceSharedExecutorSaturationTest {

    @Test
    void sharedExecutorSaturationDegradesAllOptionalProcessorsWithoutRetryAmplification()
            throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService executor = new QueryIntelligenceExecutorConfig()
                .queryIntelligenceExecutor(registry);
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try {
            for (int index = 0; index < 2; index++) {
                executor.execute(() -> {
                    running.countDown();
                    await(release);
                });
            }
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();

            for (int index = 0; index < 16; index++) {
                executor.execute(() -> await(release));
            }

            AdvancedRetrievalProperties properties =
                    mock(AdvancedRetrievalProperties.class);
            when(properties.hydeEnabled()).thenReturn(true);
            when(properties.multiQueryEnabled()).thenReturn(true);
            when(properties.modelTimeout()).thenReturn(Duration.ofMillis(50));

            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            when(builder.build()).thenReturn(mock(ChatClient.class));

            SemanticQueryMemory memory = mock(SemanticQueryMemory.class);
            QueryMemorySourceEligibility eligibility =
                    mock(QueryMemorySourceEligibility.class);
            Set<Long> accessLevels = Set.of(1L);
            when(memory.find("question", accessLevels)).thenReturn(List.of());
            when(eligibility.filter(anyList(), eq(accessLevels)))
                    .thenReturn(List.of());

            HydeQueryGenerator hyde = new HydeQueryGenerator(
                    builder,
                    executor,
                    properties,
                    memory,
                    eligibility
            );
            MultiQueryGenerator multiQuery = new MultiQueryGenerator(
                    builder,
                    executor,
                    properties
            );
            OllamaSemanticEntailmentClient entailment =
                    new OllamaSemanticEntailmentClient(
                            builder,
                            executor,
                            properties
                    );

            assertThat(hyde.generate("question", accessLevels)).isEmpty();
            assertThat(multiQuery.generate("question")).isEmpty();
            assertThat(entailment.evaluate(List.of(
                    new SemanticEntailmentClient.ClaimEvidence(
                            "claim",
                            List.of("evidence")
                    )
            ))).containsExactly(
                    SemanticEntailmentClient.EntailmentStatus.INSUFFICIENT
            );

            assertThat(registry.get("akmai.executor.rejected")
                    .tag("role", "query-intelligence")
                    .counter()
                    .count()).isEqualTo(3.0);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
