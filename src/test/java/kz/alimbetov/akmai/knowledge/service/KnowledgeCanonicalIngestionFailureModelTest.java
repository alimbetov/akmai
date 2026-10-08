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
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class KnowledgeCanonicalIngestionFailureModelTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Test
    void inProgressCanonicalRequestStopsBeforeMapping() {
        Fixture fixture = fixture();
        CanonicalDocument source = canonical("doc-busy");
        when(fixture.fingerprint.fingerprint(source)).thenReturn("fp");
        when(fixture.idempotency.claim("key", "doc-busy", "fp", LEASE))
                .thenReturn(IngestionIdempotencyRepository.ClaimResult.inProgress(9L));

        assertThatThrownBy(() -> fixture.service.addCanonical(source, "key"))
                .isInstanceOfSatisfying(
                        IdempotencyConflictException.class,
                        exception -> {
                            assertThat(exception.code())
                                    .isEqualTo("INGESTION_IN_PROGRESS");
                            assertThat(exception.retryAfterSeconds()).isEqualTo(9L);
                        }
                );

        verifyNoInteractions(
                fixture.mapper,
                fixture.chunker,
                fixture.executor,
                fixture.persistence
        );
        verify(fixture.idempotency, never()).renew(any(), any());
        verify(fixture.idempotency, never()).fail(any(), any());
    }

    @Test
    void canonicalDocumentWithoutSearchableChunksFailsClaimBeforeEnrichment() {
        Fixture fixture = fixture();
        CanonicalDocument source = canonical("doc-empty");
        IngestionIdempotencyContext context = context();
        KnowledgeDocument preparedDocument = prepared("doc-empty");
        CanonicalDocumentMapper.PreparedCanonicalDocument prepared =
                new CanonicalDocumentMapper.PreparedCanonicalDocument(
                        preparedDocument,
                        List.of()
                );
        when(fixture.fingerprint.fingerprint(source)).thenReturn("fp");
        when(fixture.idempotency.claim("key", "doc-empty", "fp", LEASE))
                .thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        when(fixture.mapper.prepare(source)).thenReturn(prepared);
        when(fixture.chunker.chunk(preparedDocument, List.of()))
                .thenReturn(List.of());
        when(fixture.chunker.searchableChunkCount(anyList())).thenReturn(0L);

        assertThatThrownBy(() -> fixture.service.addCanonical(source, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no indexable chunks");

        verify(fixture.idempotency).fail(
                context,
                "Document produced no indexable chunks after normalization"
        );
        verifyNoInteractions(fixture.executor, fixture.persistence);
    }

    @Test
    void canonicalChunkingFailureMarksClaimFailedAndStopsDownstreamWork() {
        Fixture fixture = fixture();
        CanonicalDocument source = canonical("doc-chunk-failure");
        IngestionIdempotencyContext context = context();
        KnowledgeDocument preparedDocument = prepared("doc-chunk-failure");
        CanonicalDocumentMapper.PreparedCanonicalDocument prepared =
                new CanonicalDocumentMapper.PreparedCanonicalDocument(
                        preparedDocument,
                        List.of()
                );
        when(fixture.fingerprint.fingerprint(source)).thenReturn("fp");
        when(fixture.idempotency.claim(
                "key",
                "doc-chunk-failure",
                "fp",
                LEASE
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.claimed(context));
        when(fixture.mapper.prepare(source)).thenReturn(prepared);
        when(fixture.chunker.chunk(preparedDocument, List.of()))
                .thenThrow(new IllegalStateException("chunking failed"));

        assertThatThrownBy(() -> fixture.service.addCanonical(source, "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chunking failed");

        verify(fixture.idempotency).fail(context, "chunking failed");
        verifyNoInteractions(fixture.executor, fixture.persistence);
    }

    @Test
    void blankCanonicalIdempotencyKeyDoesNotFingerprintOrClaim() {
        Fixture fixture = fixture();
        CanonicalDocument source = canonical("doc-blank-key");
        KnowledgeDocument preparedDocument = prepared("doc-blank-key");
        CanonicalDocumentMapper.PreparedCanonicalDocument prepared =
                new CanonicalDocumentMapper.PreparedCanonicalDocument(
                        preparedDocument,
                        List.of()
                );
        when(fixture.mapper.prepare(source)).thenReturn(prepared);
        when(fixture.chunker.chunk(preparedDocument, List.of()))
                .thenReturn(List.of(new kz.alimbetov.akmai.knowledge.model.KnowledgeChunk(
                        "chunk-1",
                        "doc-blank-key",
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
                )));
        when(fixture.chunker.searchableChunkCount(anyList())).thenReturn(1L);
        when(fixture.executor.execute(anyList())).thenReturn(List.of());

        fixture.service.addCanonical(source, " ");

        verifyNoInteractions(fixture.fingerprint, fixture.idempotency);
        verify(fixture.persistence).persist(anyList(), eq(null), any(), eq(1L));
    }

    private Fixture fixture() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        CanonicalDocumentMapper mapper = mock(CanonicalDocumentMapper.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                new IdempotencyProperties(LEASE),
                mapper
        );
        return new Fixture(
                service,
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                mapper
        );
    }

    private CanonicalDocument canonical(String documentId) {
        return new CanonicalDocument(
                documentId,
                "v1",
                "Title",
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                1L,
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

    private KnowledgeDocument prepared(String documentId) {
        return new KnowledgeDocument(
                documentId,
                "Title",
                "Canonical text",
                "en",
                KnowledgeDomain.GENERAL,
                Map.of("access_level", 1L)
        );
    }

    private IngestionIdempotencyContext context() {
        return new IngestionIdempotencyContext(
                "key",
                UUID.randomUUID(),
                "fp"
        );
    }

    private record Fixture(
            KnowledgeIngestionService service,
            HierarchicalChunker chunker,
            ParallelIngestionExecutor executor,
            PersistenceCoordinator persistence,
            IngestionIdempotencyRepository idempotency,
            CanonicalRequestFingerprint fingerprint,
            CanonicalDocumentMapper mapper
    ) {
    }
}
