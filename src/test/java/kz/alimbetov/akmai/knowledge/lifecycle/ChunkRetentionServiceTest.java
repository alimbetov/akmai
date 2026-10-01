package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
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
    private static final RetentionClaim CLAIM = new RetentionClaim(
            "doc-1",
            7,
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            "pod-a",
            NOW.plusSeconds(600)
    );

    @Test
    void deletesOnlyClaimedGenerationAndMarksDeleted() {
        Fixture fixture = fixture();
        when(fixture.lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(fixture.lifecycle.isCurrentClaim(CLAIM, NOW)).thenReturn(true);
        when(fixture.vectorGenerations.findVectorIds("doc-1", 7))
                .thenReturn(List.of("doc-1::g7::chunk-a", "doc-1::g7::chunk-b"));
        when(fixture.lifecycle.markDeleted(CLAIM, NOW)).thenReturn(true);

        RetentionCleanupResult result = fixture.service.cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.DELETED);
        assertThat(result.deletedChunks()).isEqualTo(2);

        InOrder order = inOrder(
                fixture.lifecycle,
                fixture.vectorGenerations,
                fixture.vectors,
                fixture.identifiers,
                fixture.projections
        );
        order.verify(fixture.lifecycle).markDeleting(CLAIM, NOW);
        order.verify(fixture.vectorGenerations).findVectorIds("doc-1", 7);
        order.verify(fixture.lifecycle).isCurrentClaim(CLAIM, NOW);
        order.verify(fixture.vectors)
                .delete(List.of("doc-1::g7::chunk-a", "doc-1::g7::chunk-b"));
        order.verify(fixture.lifecycle).isCurrentClaim(CLAIM, NOW);
        order.verify(fixture.identifiers).deleteByDocumentId("doc-1");
        order.verify(fixture.projections).deleteByDocumentId("doc-1");
        order.verify(fixture.vectorGenerations).deleteGeneration("doc-1", 7);
        order.verify(fixture.lifecycle).markDeleted(CLAIM, NOW);
    }

    @Test
    void staleClaimBeforeVectorDeleteDoesNotDeleteAnything() {
        Fixture fixture = fixture();
        when(fixture.lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(fixture.vectorGenerations.findVectorIds("doc-1", 7))
                .thenReturn(List.of("doc-1::g7::chunk-a"));
        when(fixture.lifecycle.isCurrentClaim(CLAIM, NOW)).thenReturn(false);

        RetentionCleanupResult result = fixture.service.cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.STALE_CLAIM);
        verify(fixture.vectors, never()).delete(anyList());
        verify(fixture.identifiers, never()).deleteByDocumentId("doc-1");
        verify(fixture.projections, never()).deleteByDocumentId("doc-1");
        verify(fixture.vectorGenerations, never()).deleteGeneration("doc-1", 7);
    }

    @Test
    void staleClaimAfterVectorDeletePreventsPostgresDeletion() {
        Fixture fixture = fixture();
        when(fixture.lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(fixture.vectorGenerations.findVectorIds("doc-1", 7))
                .thenReturn(List.of("doc-1::g7::old-chunk"));
        when(fixture.lifecycle.isCurrentClaim(CLAIM, NOW)).thenReturn(true, false);

        RetentionCleanupResult result = fixture.service.cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.STALE_CLAIM);
        verify(fixture.vectors).delete(List.of("doc-1::g7::old-chunk"));
        verify(fixture.identifiers, never()).deleteByDocumentId("doc-1");
        verify(fixture.projections, never()).deleteByDocumentId("doc-1");
        verify(fixture.vectorGenerations, never()).deleteGeneration("doc-1", 7);
    }

    @Test
    void failureIsPersistedForRetry() {
        Fixture fixture = fixture();
        when(fixture.lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(fixture.vectorGenerations.findVectorIds("doc-1", 7))
                .thenReturn(List.of("doc-1::g7::chunk-a"));
        when(fixture.lifecycle.isCurrentClaim(CLAIM, NOW)).thenReturn(true);
        doThrow(new IllegalStateException("vector unavailable"))
                .when(fixture.vectors)
                .delete(List.of("doc-1::g7::chunk-a"));

        RetentionCleanupResult result = fixture.service.cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.FAILED);
        verify(fixture.lifecycle).markFailed(
                CLAIM,
                NOW,
                "IllegalStateException: vector unavailable"
        );
        verify(fixture.identifiers, never()).deleteByDocumentId("doc-1");
        verify(fixture.projections, never()).deleteByDocumentId("doc-1");
    }

    @Test
    void emptyGenerationIsAnIdempotentSuccessfulCleanup() {
        Fixture fixture = fixture();
        when(fixture.lifecycle.markDeleting(CLAIM, NOW)).thenReturn(true);
        when(fixture.vectorGenerations.findVectorIds("doc-1", 7)).thenReturn(List.of());
        when(fixture.lifecycle.isCurrentClaim(CLAIM, NOW)).thenReturn(true);
        when(fixture.lifecycle.markDeleted(CLAIM, NOW)).thenReturn(true);

        RetentionCleanupResult result = fixture.service.cleanup(CLAIM);

        assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.DELETED);
        verify(fixture.vectors, never()).delete(anyList());
        verify(fixture.identifiers).deleteByDocumentId("doc-1");
        verify(fixture.projections).deleteByDocumentId("doc-1");
        verify(fixture.vectorGenerations).deleteGeneration("doc-1", 7);
    }

    private Fixture fixture() {
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        VectorStore vectors = mock(VectorStore.class);
        VectorGenerationRepository vectorGenerations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        when(lock.acquire("doc-1")).thenReturn(mock(DocumentOperationLock.LockHandle.class));

        return new Fixture(
                lifecycle,
                projections,
                identifiers,
                vectors,
                vectorGenerations,
                new ChunkRetentionService(
                        lifecycle,
                        projections,
                        identifiers,
                        vectors,
                        vectorGenerations,
                        lock,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                )
        );
    }

    private record Fixture(
            DocumentLifecycleRepository lifecycle,
            SearchProjectionRepository projections,
            IdentifierSearchIndex identifiers,
            VectorStore vectors,
            VectorGenerationRepository vectorGenerations,
            ChunkRetentionService service
    ) {
    }
}
