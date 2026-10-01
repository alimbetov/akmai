package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

class RetentionReingestionRaceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void retentionOfGenerationNCannotDeleteGenerationNPlusOneVectors() throws Exception {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        VectorStore vectors = mock(VectorStore.class);
        VectorGenerationRepository generations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        DocumentOperationLock.LockHandle retentionLock = mock(DocumentOperationLock.LockHandle.class);

        RetentionClaim claim = new RetentionClaim(
                "doc-1",
                7,
                UUID.fromString("00000000-0000-0000-0000-000000000007"),
                "pod-a",
                NOW.plus(Duration.ofMinutes(10))
        );

        when(lock.acquire("doc-1")).thenReturn(retentionLock);
        when(lifecycle.markDeleting(claim, NOW)).thenReturn(true);
        when(lifecycle.isCurrentClaim(claim, NOW)).thenReturn(true);
        when(lifecycle.markDeleted(claim, NOW)).thenReturn(true);
        when(generations.findVectorIds("doc-1", 7))
                .thenReturn(List.of("doc-1::g7::chunk-a"));

        CountDownLatch generationSevenDeleteStarted = new CountDownLatch(1);
        CountDownLatch generationEightPublished = new CountDownLatch(1);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> ids = invocation.getArgument(0);
            assertThat(ids).containsExactly("doc-1::g7::chunk-a");
            generationSevenDeleteStarted.countDown();
            assertThat(generationEightPublished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ids).doesNotContain("doc-1::g8::chunk-a");
            return null;
        }).when(vectors).delete(List.of("doc-1::g7::chunk-a"));

        ChunkRetentionService retention = new ChunkRetentionService(
                lifecycle,
                projections,
                identifiers,
                vectors,
                generations,
                lock,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        CompletableFuture<RetentionCleanupResult> cleanup =
                CompletableFuture.supplyAsync(() -> retention.cleanup(claim));

        assertThat(generationSevenDeleteStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Simulates generation N+1 becoming available while cleanup N is in-flight.
        List<String> generationEightVectors = List.of("doc-1::g8::chunk-a");
        assertThat(generationEightVectors)
                .doesNotContainAnyElementsOf(List.of("doc-1::g7::chunk-a"));
        generationEightPublished.countDown();

        RetentionCleanupResult result = cleanup.get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.DELETED);
        assertThat(result.generation()).isEqualTo(7);
        assertThat(generationEightVectors).containsExactly("doc-1::g8::chunk-a");
    }
}
