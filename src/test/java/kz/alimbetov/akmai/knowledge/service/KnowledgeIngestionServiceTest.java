package kz.alimbetov.akmai.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeIngestionServiceTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Test
    void canonicalizesLanguageAliasesBeforeChunkingAndPersistence() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        when(chunker.chunk(any())).thenReturn(List.of(chunk()));
        when(chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(executor.execute(anyList())).thenReturn(List.of());

        KnowledgeIngestionService service = service(
                chunker,
                executor,
                persistence,
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class)
        );

        service.addText(request("doc-1", "RUSSIAN", 7L, Map.of()));

        ArgumentCaptor<KnowledgeDocument> captor =
                ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(chunker).chunk(captor.capture());
        assertThat(captor.getValue().language()).isEqualTo("ru");
        assertThat(captor.getValue().metadata())
                .containsEntry("source", "source")
                .containsEntry("access_level", 7L);
        verify(persistence).persist(anyList(), any(), any(), eq(7L));
    }

    @Test
    void requestSourceAndAccessLevelOverrideConflictingMetadata() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        when(chunker.chunk(any())).thenReturn(List.of(chunk()));
        when(chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(executor.execute(anyList())).thenReturn(List.of());

        KnowledgeIngestionService service = service(
                chunker,
                executor,
                persistence,
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class)
        );

        service.addText(request(
                "doc-metadata",
                "en",
                9L,
                Map.of("source", "spoofed", "access_level", 999L)
        ));

        ArgumentCaptor<KnowledgeDocument> captor =
                ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(chunker).chunk(captor.capture());
        assertThat(captor.getValue().metadata())
                .containsEntry("source", "source")
                .containsEntry("access_level", 9L);
    }

    @Test
    void acceptsExpandedLanguageAliasBeforeChunking() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        when(chunker.chunk(any())).thenReturn(List.of(chunk()));
        when(chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(executor.execute(anyList())).thenReturn(List.of());

        KnowledgeIngestionService service = service(
                chunker,
                executor,
                persistence,
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class)
        );

        service.addText(request("doc-de", "Deutsch", 1L, Map.of()));

        ArgumentCaptor<KnowledgeDocument> captor =
                ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(chunker).chunk(captor.capture());
        assertThat(captor.getValue().language()).isEqualTo("de");
    }

    @Test
    void persistsClaimedIngestionAndRenewsLeaseAcrossStages() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        IngestionIdempotencyContext context = new IngestionIdempotencyContext(
                "ingest-key",
                UUID.randomUUID(),
                "fingerprint"
        );
        AddKnowledgeRequest request = request("doc-claimed", "en", 7L, Map.of());

        when(fingerprint.fingerprint(request)).thenReturn("fingerprint");
        when(idempotency.claim(
                "ingest-key",
                "doc-claimed",
                "fingerprint",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        when(chunker.chunk(any())).thenReturn(List.of(chunk()));
        when(chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(executor.execute(anyList())).thenReturn(List.of());

        KnowledgeIngestionResponse response = service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint
        ).addText(request, "ingest-key");

        assertThat(response.documentId()).isEqualTo("doc-claimed");
        assertThat(response.chunkCount()).isEqualTo(1);
        verify(idempotency, times(3)).renew(context, LEASE);
        verify(persistence).persist(anyList(), eq(context), eq(response), eq(7L));
        verify(idempotency, never()).fail(any(), any());
    }

    @Test
    void returnsReplayWithoutRepeatingIngestionWork() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        AddKnowledgeRequest request = request("doc-replay", "en", 1L, Map.of());
        KnowledgeIngestionResponse replay = new KnowledgeIngestionResponse("doc-replay", 4);

        when(fingerprint.fingerprint(request)).thenReturn("fingerprint");
        when(idempotency.claim(
                "replay-key",
                "doc-replay",
                "fingerprint",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.replay(replay));

        KnowledgeIngestionResponse response = service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint
        ).addText(request, "replay-key");

        assertThat(response).isSameAs(replay);
        verifyNoInteractions(chunker, executor, persistence);
        verify(idempotency, never()).renew(any(), any());
        verify(idempotency, never()).fail(any(), any());
    }

    @Test
    void rejectsConcurrentInProgressRequestBeforeChunking() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        AddKnowledgeRequest request = request("doc-busy", "en", 1L, Map.of());

        when(fingerprint.fingerprint(request)).thenReturn("fingerprint");
        when(idempotency.claim(
                "busy-key",
                "doc-busy",
                "fingerprint",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.inProgress(12L));

        assertThatThrownBy(() -> service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint
        ).addText(request, "busy-key"))
                .isInstanceOfSatisfying(
                        IdempotencyConflictException.class,
                        exception -> {
                            assertThat(exception.code()).isEqualTo("INGESTION_IN_PROGRESS");
                            assertThat(exception.retryAfterSeconds()).isEqualTo(12L);
                        }
                );

        verifyNoInteractions(chunker, executor, persistence);
        verify(idempotency, never()).renew(any(), any());
        verify(idempotency, never()).fail(any(), any());
    }

    @Test
    void marksClaimFailedWhenEnrichmentFails() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        IngestionIdempotencyContext context = new IngestionIdempotencyContext(
                "failure-key",
                UUID.randomUUID(),
                "fingerprint"
        );
        AddKnowledgeRequest request = request("doc-failure", "en", 1L, Map.of());

        when(fingerprint.fingerprint(request)).thenReturn("fingerprint");
        when(idempotency.claim(
                "failure-key",
                "doc-failure",
                "fingerprint",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        when(chunker.chunk(any())).thenReturn(List.of(chunk()));
        when(chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(executor.execute(anyList()))
                .thenThrow(new IllegalStateException("embedding unavailable"));

        assertThatThrownBy(() -> service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint
        ).addText(request, "failure-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding unavailable");

        verify(idempotency, times(2)).renew(context, LEASE);
        verify(idempotency).fail(context, "embedding unavailable");
        verifyNoInteractions(persistence);
    }

    @Test
    void rejectsDocumentThatNormalizesToNoIndexableChunks() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        when(chunker.chunk(any())).thenReturn(List.of());
        when(chunker.searchableChunkCount(anyList())).thenReturn(0L);

        KnowledgeIngestionService service = service(
                chunker,
                executor,
                persistence,
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class)
        );

        assertThatThrownBy(() -> service.addText(request(
                "doc-empty",
                "en",
                1L,
                Map.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no indexable chunks");

        verify(executor, never()).execute(anyList());
        verify(persistence, never()).persist(anyList(), any(), any(), any(Long.class));
    }

    @Test
    void rejectsUnsupportedLanguageBeforeAnyChunkingWork() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        KnowledgeIngestionService service = service(
                chunker,
                mock(ParallelIngestionExecutor.class),
                mock(PersistenceCoordinator.class),
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class)
        );

        assertThatThrownBy(() -> service.addText(request(
                "doc-unsupported",
                "ja",
                1L,
                Map.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported language");

        verifyNoInteractions(chunker);
    }

    private KnowledgeIngestionService service(
            HierarchicalChunker chunker,
            ParallelIngestionExecutor executor,
            PersistenceCoordinator persistence,
            IngestionIdempotencyRepository idempotency,
            CanonicalRequestFingerprint fingerprint
    ) {
        return new KnowledgeIngestionService(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                new IdempotencyProperties(LEASE)
        );
    }

    private AddKnowledgeRequest request(
            String documentId,
            String language,
            long accessLevel,
            Map<String, Object> metadata
    ) {
        return new AddKnowledgeRequest(
                documentId,
                "Title",
                "Text",
                "source",
                language,
                KnowledgeDomain.GENERAL,
                accessLevel,
                metadata
        );
    }

    private KnowledgeChunk chunk() {
        return new KnowledgeChunk(
                "chunk-1",
                "doc",
                null,
                0,
                "Text",
                "Text",
                "Text",
                "Title",
                "",
                "en",
                KnowledgeDomain.GENERAL,
                List.of(),
                Map.of()
        );
    }
}
