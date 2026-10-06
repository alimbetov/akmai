package kz.alimbetov.akmai.rag.assurance.read;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalObserver;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.SemanticRerankScorer;
import org.junit.jupiter.api.Test;

class RerankingContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void timeoutReturnsOriginalCandidateSetWithoutCorruption() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        Future<List<RetrievalHit>> future = mock(Future.class);
        when(executor.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new TimeoutException("contract timeout"));

        Reranker reranker = new Reranker(
                mock(SemanticRerankScorer.class),
                properties(),
                mock(RetrievalObserver.class),
                executor
        );
        List<RetrievalHit> input = candidates();

        List<RetrievalHit> output = reranker.rerank(input, "contract question");

        assertDoesNotThrow(() -> RagAssertions.retrieval(output)
                .forFixture("r06-timeout-fallback")
                .hasSameCanonicalSequenceAs(input)
                .hasNoDuplicateCanonicalHits());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executorFailureReturnsOriginalCandidateSetWithoutBroadening() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Callable.class)))
                .thenThrow(new RejectedExecutionException("contract rejection"));

        Reranker reranker = new Reranker(
                mock(SemanticRerankScorer.class),
                properties(),
                mock(RetrievalObserver.class),
                executor
        );
        List<RetrievalHit> input = candidates();

        List<RetrievalHit> output = reranker.rerank(input, "contract question");

        assertDoesNotThrow(() -> RagAssertions.retrieval(output)
                .forFixture("r06-submit-fallback")
                .hasSameCanonicalSequenceAs(input)
                .hasNoDuplicateCanonicalHits());
    }

    private List<RetrievalHit> candidates() {
        return List.of(
                hit("first", 0.9),
                hit("second", 0.8),
                hit("third", 0.7)
        );
    }

    private RetrievalHit hit(String chunkId, double score) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                2L,
                chunkId,
                "evidence " + chunkId,
                Map.of("authorityTier", 2),
                List.of(),
                score
        );
    }

    private RetrievalProperties properties() {
        return new RetrievalProperties(
                2,
                32,
                10,
                0.0,
                10,
                10,
                10,
                60,
                3,
                1,
                3,
                2048,
                8,
                4,
                true,
                3,
                Duration.ofMillis(25),
                0.5
        );
    }
}
