package kz.alimbetov.akmai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

class AnswerGenerationServiceTest {

    @Test
    void blockingChatCallFailsWithinConfiguredApplicationDeadline() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .content())
                .thenAnswer(invocation -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return "late";
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AnswerGenerationService service = new AnswerGenerationService(
                    builder,
                    executor,
                    properties(Duration.ofMillis(25)),
                    new RagPromptTemplate()
            );

            long started = System.nanoTime();
            assertThatThrownBy(() -> service.generate("question", "{}"))
                    .isInstanceOf(AnswerGenerationException.class)
                    .hasMessageContaining("timed out");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timedOutModelCallIsInterruptedAndNextHealthyCallUsesSameWorker()
            throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(chatClient);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        when(chatClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .content())
                .thenAnswer(invocation -> {
                    if (calls.incrementAndGet() == 1) {
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }
                    return "healthy";
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AnswerGenerationService service = new AnswerGenerationService(
                    builder,
                    executor,
                    properties(Duration.ofMillis(50)),
                    new RagPromptTemplate()
            );

            assertThatThrownBy(() -> service.generate("slow", "{}"))
                    .isInstanceOf(AnswerGenerationException.class)
                    .hasMessageContaining("timed out");
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();

            long healthyStarted = System.nanoTime();
            assertThat(service.generate("healthy", "{}"))
                    .isEqualTo("healthy");
            assertThat(Duration.ofNanos(System.nanoTime() - healthyStarted))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            executor.shutdownNow();
        }
    }

    private RetrievalProperties properties(Duration answerTimeout) {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();
        return new RetrievalProperties(
                defaults.parallelism(),
                defaults.queueCapacity(),
                defaults.vectorTopK(),
                defaults.vectorSimilarityThreshold(),
                defaults.lexicalLimit(),
                defaults.identifierLimit(),
                defaults.referenceLimit(),
                defaults.rrfK(),
                defaults.expansionSeeds(),
                defaults.expansionRadius(),
                defaults.expansionMax(),
                defaults.contextMaxTokens(),
                defaults.contextMaxChunks(),
                defaults.contextMaxChunksPerDocument(),
                defaults.rerankerEnabled(),
                defaults.rerankerCandidates(),
                defaults.rerankerTimeout(),
                defaults.rerankerFusedWeight(),
                defaults.requestTimeout(),
                defaults.strategyTimeout(),
                answerTimeout,
                defaults.embeddingHttpTimeout(),
                defaults.contextExpansionMaxChunks(),
                defaults.answerReservedTokens()
        );
    }
}
