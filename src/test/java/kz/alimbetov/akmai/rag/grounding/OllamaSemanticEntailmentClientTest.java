package kz.alimbetov.akmai.rag.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kz.alimbetov.akmai.rag.query.AdvancedRetrievalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class OllamaSemanticEntailmentClientTest {

    @Test
    void emptyBatchDoesNotConsumeExecutorCapacity() {
        Fixture fixture = fixture();

        assertThat(fixture.client.evaluate(List.of())).isEmpty();

        verify(fixture.executor, never()).submit(
                org.mockito.ArgumentMatchers.<Callable<String>>any()
        );
    }

    @Test
    void executorRejectionFailsClosedForEveryClaim() {
        Fixture fixture = fixture();
        when(fixture.executor.submit(
                org.mockito.ArgumentMatchers.<Callable<String>>any()
        )).thenThrow(new RejectedExecutionException("saturated"));

        assertThat(fixture.client.evaluate(claims()))
                .containsExactly(
                        SemanticEntailmentClient.EntailmentStatus.INSUFFICIENT,
                        SemanticEntailmentClient.EntailmentStatus.INSUFFICIENT
                );
    }

    @Test
    void timeoutCancelsModelTaskAndFailsClosedForEveryClaim() throws Exception {
        Fixture fixture = fixture();
        @SuppressWarnings("unchecked")
        Future<String> future = mock(Future.class);
        when(fixture.executor.submit(
                org.mockito.ArgumentMatchers.<Callable<String>>any()
        )).thenReturn(future);
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new TimeoutException("model timeout"));

        assertThat(fixture.client.evaluate(claims()))
                .containsExactly(
                        SemanticEntailmentClient.EntailmentStatus.INSUFFICIENT,
                        SemanticEntailmentClient.EntailmentStatus.INSUFFICIENT
                );
        verify(future).cancel(true);
    }

    private static List<SemanticEntailmentClient.ClaimEvidence> claims() {
        return List.of(
                new SemanticEntailmentClient.ClaimEvidence(
                        "claim one",
                        List.of("evidence one")
                ),
                new SemanticEntailmentClient.ClaimEvidence(
                        "claim two",
                        List.of("evidence two")
                )
        );
    }

    private static Fixture fixture() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        ExecutorService executor = mock(ExecutorService.class);
        AdvancedRetrievalProperties properties = mock(AdvancedRetrievalProperties.class);
        when(properties.modelTimeout()).thenReturn(Duration.ofMillis(50));
        OllamaSemanticEntailmentClient client = new OllamaSemanticEntailmentClient(
                builder,
                executor,
                properties
        );
        return new Fixture(client, executor);
    }

    private record Fixture(
            OllamaSemanticEntailmentClient client,
            ExecutorService executor
    ) {
    }
}
