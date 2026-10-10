package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class HydeQueryGeneratorTest {

    @Test
    void disabledGeneratorDoesNotConsumeExecutorCapacity() {
        Fixture fixture = fixture(false);

        assertThat(fixture.generator.generate("question", Set.of(1L))).isEmpty();

        verify(fixture.executor, never()).submit(
                org.mockito.ArgumentMatchers.<Callable<String>>any()
        );
    }

    @Test
    void executorRejectionFallsBackWithoutEscalatingOverload() {
        Fixture fixture = fixture(true);
        Set<Long> accessLevels = Set.of(1L);
        when(fixture.memory.find("question", accessLevels)).thenReturn(List.of());
        when(fixture.eligibility.filter(anyList(), eq(accessLevels)))
                .thenReturn(List.of());
        when(fixture.executor.submit(
                org.mockito.ArgumentMatchers.<Callable<String>>any()
        )).thenThrow(new RejectedExecutionException("saturated"));

        assertThat(fixture.generator.generate("question", accessLevels)).isEmpty();
    }

    @Test
    void timeoutCancelsModelTaskAndReturnsEmptyFallback() throws Exception {
        Fixture fixture = fixture(true);
        Set<Long> accessLevels = Set.of(1L);
        @SuppressWarnings("unchecked")
        Future<String> future = mock(Future.class);
        when(fixture.memory.find("question", accessLevels)).thenReturn(List.of());
        when(fixture.eligibility.filter(anyList(), eq(accessLevels)))
                .thenReturn(List.of());
        when(fixture.executor.submit(
                org.mockito.ArgumentMatchers.<Callable<String>>any()
        )).thenReturn(future);
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new TimeoutException("model timeout"));

        assertThat(fixture.generator.generate("question", accessLevels)).isEmpty();

        verify(future).cancel(true);
    }

    private static Fixture fixture(boolean enabled) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        ExecutorService executor = mock(ExecutorService.class);
        AdvancedRetrievalProperties properties = mock(AdvancedRetrievalProperties.class);
        when(properties.hydeEnabled()).thenReturn(enabled);
        when(properties.modelTimeout()).thenReturn(Duration.ofMillis(50));
        SemanticQueryMemory memory = mock(SemanticQueryMemory.class);
        QueryMemorySourceEligibility eligibility = mock(QueryMemorySourceEligibility.class);
        HydeQueryGenerator generator = new HydeQueryGenerator(
                builder,
                executor,
                properties,
                memory,
                eligibility
        );
        return new Fixture(generator, executor, memory, eligibility);
    }

    private record Fixture(
            HydeQueryGenerator generator,
            ExecutorService executor,
            SemanticQueryMemory memory,
            QueryMemorySourceEligibility eligibility
    ) {
    }
}
