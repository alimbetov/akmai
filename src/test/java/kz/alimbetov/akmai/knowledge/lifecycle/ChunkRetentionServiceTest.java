package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.vectorstore.VectorStore;

class ChunkRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T22:30:00Z");
    private static final RetentionClaim CLAIM = new RetentionClaim("doc-1", 7, UUID.fromString("00000000-0000-0000-0000-000000000007"), "pod-a", NOW.plusSeconds(600));

    @Test
    void deletesAllRetrievalStateAndMarksDeleted() {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        VectorStore vectors = mock(VectorStore.class);
        VectorGenerationRepository vectorGenerations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        when(lock.acquire("doc-1")).thenReturn(mock(DocumentOperationLock.LockHandle.class));
        when(lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(lifecycle.isCurrentClaim(CLAIM)).thenReturn(true);
        when(vectorGenerations.findVectorIds("doc-1", 7))
                .thenReturn(List.of("doc-1::g7::chunk-a", "doc-1::g7::chunk-b"));
        when(lifecycle.markDeleted(CLAIM, NOW)).thenReturn(true);

        RetentionCleanupResult result =
                service(lifecycle, projections, identifiers, vectors).cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.DELETED);
        assertThat(result.deletedChunks()).isEqualTo(2);

        InOrder order = inOrder(lifecycle, projections, identifiers, vectors);
        order.verify(lifecycle).markDeleting(CLAIM, NOW);
        order.verify(lifecycle).isCurrentClaim(CLAIM, NOW);
        order.verify(lifecycle).isCurrentClaim(CLAIM);
        order.verify(vectors).delete(List.of("chunk-a", "chunk-b"));
        order.verify(lifecycle).isCurrentClaim(CLAIM);
        order.verify(identifiers).deleteByDocumentId("doc-1");
        order.verify(projections).deleteByDocumentId("doc-1");
        order.verify(lifecycle).markDeleted(CLAIM, NOW);
    }

    @Test
    void staleClaimBeforeVectorDeleteDoesNotDeleteAnything() {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        VectorStore vectors = mock(VectorStore.class);
        VectorGenerationRepository vectorGenerations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        when(lock.acquire("doc-1")).thenReturn(mock(DocumentOperationLock.LockHandle.class));
        when(lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(projections.findChunkIdsByDocumentId("doc-1"))
                .thenReturn(List.of("chunk-a"));
        when(lifecycle.isCurrentClaim(CLAIM)).thenReturn(false);

        RetentionCleanupResult result =
                service(lifecycle, projections, identifiers, vectors).cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.STALE_CLAIM);
        verify(vectors, never()).delete(List.of("chunk-a"));
        verify(identifiers, never()).deleteByDocumentId("doc-1");
        verify(projections, never()).deleteByDocumentId("doc-1");
    }

    @Test
    void reingestionDuringVectorDeletePreventsPostgresDeletion() {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        VectorStore vectors = mock(VectorStore.class);
        VectorGenerationRepository vectorGenerations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        when(lock.acquire("doc-1")).thenReturn(mock(DocumentOperationLock.LockHandle.class));
        when(lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(projections.findChunkIdsByDocumentId("doc-1"))
                .thenReturn(List.of("old-chunk"));
        when(lifecycle.isCurrentClaim(CLAIM)).thenReturn(true, false);

        RetentionCleanupResult result =
                service(lifecycle, projections, identifiers, vectors).cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.STALE_CLAIM);
        verify(vectors).delete(List.of("old-chunk"));
        verify(identifiers, never()).deleteByDocumentId("doc-1");
        verify(projections, never()).deleteByDocumentId("doc-1");
    }

    @Test
    void failureIsPersistedForRetry() {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        VectorStore vectors = mock(VectorStore.class);
        VectorGenerationRepository vectorGenerations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        when(lock.acquire("doc-1")).thenReturn(mock(DocumentOperationLock.LockHandle.class));
        when(lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(lifecycle.isCurrentClaim(CLAIM)).thenReturn(true);
        when(projections.findChunkIdsByDocumentId("doc-1"))
                .thenReturn(List.of("chunk-a"));
        doThrow(new IllegalStateException("vector unavailable"))
                .when(vectors).delete(List.of("chunk-a"));

        RetentionCleanupResult result =
                service(lifecycle, projections, identifiers, vectors).cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.FAILED);
        verify(lifecycle).markFailed(
                CLAIM,
                NOW,
                "IllegalStateException: vector unavailable"
        );
        verify(identifiers, never()).deleteByDocumentId("doc-1");
        verify(projections, never()).deleteByDocumentId("doc-1");
    }

    @Test
    void emptyCanonicalStateIsAnIdempotentSuccessfulCleanup() {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        VectorStore vectors = mock(VectorStore.class);
        VectorGenerationRepository vectorGenerations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        when(lock.acquire("doc-1")).thenReturn(mock(DocumentOperationLock.LockHandle.class));
        when(lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(lifecycle.isCurrentClaim(CLAIM)).thenReturn(true);
        when(projections.findChunkIdsByDocumentId("doc-1")).thenReturn(List.of());
        when(lifecycle.markDeleted(CLAIM, NOW)).thenReturn(true);

        RetentionCleanupResult result =
                service(lifecycle, projections, identifiers, vectors).cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.DELETED);
        verify(vectors, never()).delete(org.mockito.ArgumentMatchers.anyList());
        verify(identifiers).deleteByDocumentId("doc-1");
        verify(projections).deleteByDocumentId("doc-1");
    }

    private ChunkRetentionService service(
            DocumentLifecycleRepository lifecycle,
            SearchProjectionRepository projections,
            IdentifierSearchIndex identifiers,
            VectorStore vectors,
            VectorGenerationRepository vectorGenerations,
            DocumentOperationLock lock
    ) {
        return new ChunkRetentionService(
                lifecycle,
                projections,
                identifiers,
                vectors,
                vectorGenerations,
                lock,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
