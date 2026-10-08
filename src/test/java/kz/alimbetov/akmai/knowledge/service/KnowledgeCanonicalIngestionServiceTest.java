package kz.alimbetov.akmai.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class KnowledgeCanonicalIngestionServiceTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Test
    void rejectsNullCanonicalDocumentBeforeAnyIngestionWork() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        CanonicalDocumentMapper mapper = mock(CanonicalDocumentMapper.class);

        assertThatThrownBy(() -> service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                mapper
        ).addCanonical(null, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("canonical document is required");

        verifyNoInteractions(chunker, executor, persistence, idempotency, fingerprint, mapper);
    }

    @Test
    void preparesChunksEnrichesAndPersistsCanonicalDocument() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        CanonicalDocumentMapper mapper = mock(CanonicalDocumentMapper.class);
        CanonicalDocument source = canonical("doc-canonical", 5L);
        KnowledgeDocument preparedDocument = new KnowledgeDocument(
                "doc-canonical",
                "Title",
                "Canonical text",
                "en",
                KnowledgeDomain.GENERAL,
                Map.of("access_level", 5L)
        );
        CanonicalDocumentMapper.PreparedCanonicalDocument prepared =
                new CanonicalDocumentMapper.PreparedCanonicalDocument(
                        preparedDocument,
                        List.of()
                );

        when(mapper.prepare(source)).thenReturn(prepared);
        when(chunker.chunk(preparedDocument, List.of()))
                .thenReturn(List.of(chunk("doc-canonical")));
        when(chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(executor.execute(anyList())).thenReturn(List.of());

        KnowledgeIngestionResponse response = service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                mapper
        ).addCanonical(source, null);

        assertThat(response.documentId()).isEqualTo("doc-canonical");
        assertThat(response.chunkCount()).isEqualTo(1);
        verify(mapper).prepare(source);
        verify(chunker).chunk(preparedDocument, List.of());
        verify(executor).execute(anyList());
        verify(persistence).persist(anyList(), eq(null), eq(response), eq(5L));
    }

    @Test
    void returnsCanonicalReplayWithoutRepeatingProcessing() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        CanonicalDocumentMapper mapper = mock(CanonicalDocumentMapper.class);
        CanonicalDocument source = canonical("doc-replay", 2L);
        KnowledgeIngestionResponse replay =
                new KnowledgeIngestionResponse("doc-replay", 3);

        when(fingerprint.fingerprint(source)).thenReturn("fingerprint");
        when(idempotency.claim(
                "canonical-key",
                "doc-replay",
                "fingerprint",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.replay(replay));

        KnowledgeIngestionResponse response = service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                mapper
        ).addCanonical(source, "canonical-key");

        assertThat(response).isSameAs(replay);
        verifyNoInteractions(chunker, executor, persistence, mapper);
        verify(idempotency, never()).renew(any(), any());
        verify(idempotency, never()).fail(any(), any());
    }

    @Test
    void marksCanonicalClaimFailedWhenPreparationFails() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        CanonicalDocumentMapper mapper = mock(CanonicalDocumentMapper.class);
        CanonicalDocument source = canonical("doc-failure", 2L);
        IngestionIdempotencyContext context = new IngestionIdempotencyContext(
                "canonical-failure-key",
                UUID.randomUUID(),
                "fingerprint"
        );

        when(fingerprint.fingerprint(source)).thenReturn("fingerprint");
        when(idempotency.claim(
                "canonical-failure-key",
                "doc-failure",
                "fingerprint",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        when(mapper.prepare(source))
                .thenThrow(new IllegalArgumentException("invalid canonical structure"));

        assertThatThrownBy(() -> service(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                mapper
        ).addCanonical(source, "canonical-failure-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid canonical structure");

        verify(idempotency).renew(context, LEASE);
        verify(idempotency).fail(context, "invalid canonical structure");
        verifyNoInteractions(chunker, executor, persistence);
    }

    private KnowledgeIngestionService service(
            HierarchicalChunker chunker,
            ParallelIngestionExecutor executor,
            PersistenceCoordinator persistence,
            IngestionIdempotencyRepository idempotency,
            CanonicalRequestFingerprint fingerprint,
            CanonicalDocumentMapper mapper
    ) {
        return new KnowledgeIngestionService(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                new IdempotencyProperties(LEASE),
                mapper
        );
    }

    private CanonicalDocument canonical(String documentId, long accessLevel) {
        return new CanonicalDocument(
                documentId,
                "v1",
                "Title",
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                accessLevel,
                List.of(new CanonicalDocument.Block(
                        "block-1",
                        CanonicalDocument.BlockType.PARAGRAPH,
                        "Canonical text",
                        null,
                        1,
                        1,
                        "Section",
                        null
                )),
                Map.of()
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
}
