package kz.alimbetov.akmai.knowledge.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class KnowledgeIngestionFailureModelTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Test
    void claimConflictStopsBeforeHeartbeatAndProcessing() {
        Fixture fixture = fixture();
        AddKnowledgeRequest request = request("doc-conflict");
        when(fixture.fingerprint.fingerprint(request)).thenReturn("fp");
        when(fixture.idempotency.claim("key", "doc-conflict", "fp", LEASE))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSE",
                        "key reused"
                ));

        assertThatThrownBy(() -> fixture.service.addText(request, "key"))
                .isInstanceOfSatisfying(
                        IdempotencyConflictException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.code()
                        ).isEqualTo("IDEMPOTENCY_KEY_REUSE")
                );

        verifyNoInteractions(fixture.chunker, fixture.executor, fixture.persistence);
        verify(fixture.idempotency, never()).renew(any(), any());
        verify(fixture.idempotency, never()).fail(any(), any());
    }

    @Test
    void lostClaimOnFirstHeartbeatFailsClaimAndStopsBeforeChunking() {
        Fixture fixture = fixture();
        AddKnowledgeRequest request = request("doc-lost-before-chunk");
        IngestionIdempotencyContext context = context("key", "fp");
        when(fixture.fingerprint.fingerprint(request)).thenReturn("fp");
        when(fixture.idempotency.claim(
                "key",
                "doc-lost-before-chunk",
                "fp",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        doThrow(new IdempotencyConflictException(
                "INGESTION_IDEMPOTENCY_LOST",
                "claim expired"
        )).when(fixture.idempotency).renew(context, LEASE);

        assertThatThrownBy(() -> fixture.service.addText(request, "key"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("claim expired");

        verify(fixture.idempotency).fail(context, "claim expired");
        verifyNoInteractions(fixture.chunker, fixture.executor, fixture.persistence);
    }

    @Test
    void lostClaimAfterChunkingStopsBeforeEnrichment() {
        Fixture fixture = fixture();
        AddKnowledgeRequest request = request("doc-lost-after-chunk");
        IngestionIdempotencyContext context = context("key", "fp");
        when(fixture.fingerprint.fingerprint(request)).thenReturn("fp");
        when(fixture.idempotency.claim(
                "key",
                "doc-lost-after-chunk",
                "fp",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        when(fixture.chunker.chunk(any())).thenReturn(List.of(chunk("doc-lost-after-chunk")));
        when(fixture.chunker.searchableChunkCount(anyList())).thenReturn(1L);
        org.mockito.Mockito.doNothing()
                .doThrow(new IdempotencyConflictException(
                        "INGESTION_IDEMPOTENCY_LOST",
                        "claim expired after chunking"
                ))
                .when(fixture.idempotency).renew(context, LEASE);

        assertThatThrownBy(() -> fixture.service.addText(request, "key"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("claim expired after chunking");

        verify(fixture.executor, never()).execute(anyList());
        verifyNoInteractions(fixture.persistence);
        verify(fixture.idempotency).fail(
                context,
                "claim expired after chunking"
        );
    }

    @Test
    void persistenceFailureIsPropagatedAndMarksClaimFailed() {
        Fixture fixture = fixture();
        AddKnowledgeRequest request = request("doc-persist-failure");
        IngestionIdempotencyContext context = context("key", "fp");
        when(fixture.fingerprint.fingerprint(request)).thenReturn("fp");
        when(fixture.idempotency.claim(
                "key",
                "doc-persist-failure",
                "fp",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        when(fixture.chunker.chunk(any())).thenReturn(List.of(chunk("doc-persist-failure")));
        when(fixture.chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(fixture.executor.execute(anyList())).thenReturn(List.of());
        doThrow(new IllegalStateException("publication failed"))
                .when(fixture.persistence)
                .persist(anyList(), eq(context), any(), eq(1L));

        assertThatThrownBy(() -> fixture.service.addText(request, "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publication failed");

        verify(fixture.idempotency).fail(context, "publication failed");
    }

    @Test
    void blankIdempotencyKeyBypassesIdempotencyRepository() {
        Fixture fixture = fixture();
        when(fixture.chunker.chunk(any())).thenReturn(List.of(chunk("doc-blank-key")));
        when(fixture.chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(fixture.executor.execute(anyList())).thenReturn(List.of());

        fixture.service.addText(request("doc-blank-key"), "   ");

        verifyNoInteractions(fixture.fingerprint, fixture.idempotency);
        verify(fixture.persistence).persist(anyList(), eq(null), any(), eq(1L));
    }

    private Fixture fixture() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                new IdempotencyProperties(LEASE)
        );
        return new Fixture(
                service,
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint
        );
    }

    private AddKnowledgeRequest request(String documentId) {
        return new AddKnowledgeRequest(
                documentId,
                "Title",
                "Text",
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                1L,
                Map.of()
        );
    }

    private IngestionIdempotencyContext context(String key, String fingerprint) {
        return new IngestionIdempotencyContext(
                key,
                UUID.randomUUID(),
                fingerprint
        );
    }

    private KnowledgeChunk chunk(String documentId) {
        return new KnowledgeChunk(
                "chunk-1",
                documentId,
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

    private record Fixture(
            KnowledgeIngestionService service,
            HierarchicalChunker chunker,
            ParallelIngestionExecutor executor,
            PersistenceCoordinator persistence,
            IngestionIdempotencyRepository idempotency,
            CanonicalRequestFingerprint fingerprint
    ) {
    }
}
