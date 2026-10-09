package kz.alimbetov.akmai.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResult;
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class KnowledgeCanonicalKnowledgeReplayTest {

    private static final String CONTENT_HASH = "sha256:" + "a".repeat(64);

    @Test
    void replayReturnsPublishedGenerationWithoutRunningPipelineAgain() {
        Fixture fixture = fixture();
        CanonicalKnowledgeDocument document = document();
        when(fixture.fingerprint.canonicalHash(document)).thenReturn("canonical-hash");
        when(fixture.idempotency.claim(
                "key",
                "doc-1",
                "canonical-hash",
                Duration.ofMinutes(5)
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.replay(
                new KnowledgeIngestionResponse("doc-1", 3),
                42L,
                "embedding-profile-v1"
        ));

        KnowledgeIngestionResult result = fixture.service.addCanonicalKnowledge(
                document,
                "key"
        );

        assertThat(result.publication().status())
                .isEqualTo(KnowledgeIngestionResult.PublicationStatus.REPLAYED);
        assertThat(result.publication().generation()).isEqualTo(42L);
        assertThat(result.publication().chunkCount()).isEqualTo(3);
        assertThat(result.processing().embeddingProfile())
                .isEqualTo("embedding-profile-v1");
        assertThat(result.processing().canonicalHash()).isEqualTo("canonical-hash");
        verifyNoInteractions(
                fixture.chunker,
                fixture.executor,
                fixture.persistence,
                fixture.mapper
        );
    }

    @Test
    void typedReplayFailsClosedWhenPublicationIdentityIsMissing() {
        Fixture fixture = fixture();
        CanonicalKnowledgeDocument document = document();
        when(fixture.fingerprint.canonicalHash(document)).thenReturn("canonical-hash");
        when(fixture.idempotency.claim(
                "key",
                "doc-1",
                "canonical-hash",
                Duration.ofMinutes(5)
        )).thenReturn(IngestionIdempotencyRepository.ClaimResult.replay(
                new KnowledgeIngestionResponse("doc-1", 3)
        ));

        assertThatThrownBy(() ->
                fixture.service.addCanonicalKnowledge(document, "key")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publication identity");

        verifyNoInteractions(
                fixture.chunker,
                fixture.executor,
                fixture.persistence,
                fixture.mapper
        );
    }

    private Fixture fixture() {
        HierarchicalChunker chunker = mock(HierarchicalChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        IngestionIdempotencyRepository idempotency =
                mock(IngestionIdempotencyRepository.class);
        CanonicalRequestFingerprint fingerprint = mock(CanonicalRequestFingerprint.class);
        CanonicalDocumentMapper mapper = mock(CanonicalDocumentMapper.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker,
                executor,
                persistence,
                idempotency,
                fingerprint,
                new IdempotencyProperties(Duration.ofMinutes(5)),
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

    private CanonicalKnowledgeDocument document() {
        return new CanonicalKnowledgeDocument(
                1,
                "doc-1",
                "1",
                "Document",
                "en",
                KnowledgeDomain.TECHNICAL,
                1L,
                new CanonicalKnowledgeDocument.Source(
                        CanonicalKnowledgeDocument.SourceType.FILE,
                        "file-1",
                        "1",
                        "document.pdf",
                        "application/pdf",
                        CONTENT_HASH,
                        new CanonicalKnowledgeDocument.StorageReference(
                                "rustfs",
                                "knowledge",
                                "files/file-1/document.pdf",
                                null
                        )
                ),
                new CanonicalKnowledgeDocument.Processing(
                        "pdf-parser",
                        "1.0.0",
                        Instant.parse("2026-10-09T00:00:00Z")
                ),
                List.of(new CanonicalKnowledgeDocument.Block(
                        "b-1",
                        CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                        "Content",
                        null,
                        1,
                        1,
                        List.of("Section"),
                        null
                )),
                Map.of()
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
