package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResult;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionPort;
import kz.alimbetov.akmai.observability.AsyncIngestionMetrics;
import org.junit.jupiter.api.Test;

class AsyncIngestionReplayRecoveryTest {

    @Test
    void replayedBusinessIngestionFinalizesRecoveredAsyncJob() throws Exception {
        KnowledgeIngestionPort ingestionPort =
                org.mockito.Mockito.mock(KnowledgeIngestionPort.class);
        AsyncIngestionJobRepository repository =
                org.mockito.Mockito.mock(AsyncIngestionJobRepository.class);
        AsyncIngestionMetrics metrics =
                org.mockito.Mockito.mock(AsyncIngestionMetrics.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CanonicalRequestFingerprint fingerprint =
                new CanonicalRequestFingerprint(objectMapper);
        AsyncIngestionProperties properties = new AsyncIngestionProperties(
                true,
                3,
                3,
                3,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                5,
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                1024 * 1024
        );
        AsyncIngestionHeartbeat heartbeat = new AsyncIngestionHeartbeat(
                repository,
                properties
        );
        AsyncIngestionWorker worker = new AsyncIngestionWorker(
                ingestionPort,
                repository,
                heartbeat,
                new AsyncIngestionFailureClassifier(),
                properties,
                fingerprint,
                objectMapper,
                metrics
        );

        CanonicalKnowledgeDocument document = document();
        UUID ingestionId = UUID.randomUUID();
        String internalKey = "async-ingestion:" + ingestionId;
        AsyncIngestionJob job = new AsyncIngestionJob(
                ingestionId,
                1,
                "event-recovery",
                null,
                "job-fingerprint",
                internalKey,
                document.documentId(),
                document.accessLevel(),
                document.source().type().name(),
                document.source().fileId(),
                document.source().sourceVersion(),
                document.source().contentHash(),
                fingerprint.canonicalHash(document),
                "INLINE",
                objectMapper.writeValueAsString(document),
                null,
                AsyncIngestionJobStatus.PROCESSING,
                2,
                0,
                null,
                "worker-2",
                Instant.now().plusSeconds(120),
                2L,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now().minusSeconds(30),
                Instant.now().minusSeconds(1),
                null,
                Instant.now().minusSeconds(30),
                Instant.now()
        );
        AsyncIngestionClaim claim = new AsyncIngestionClaim(
                ingestionId,
                "worker-2",
                2L,
                job
        );
        KnowledgeIngestionResult replay = new KnowledgeIngestionResult(
                1,
                document.documentId(),
                new KnowledgeIngestionResult.Source(
                        "FILE",
                        document.source().fileId(),
                        document.source().sourceVersion(),
                        document.source().contentHash()
                ),
                new KnowledgeIngestionResult.Publication(
                        KnowledgeIngestionResult.PublicationStatus.REPLAYED,
                        42L,
                        7
                ),
                new KnowledgeIngestionResult.Processing(
                        1,
                        fingerprint.canonicalHash(document),
                        "pdf-parser",
                        "1.0",
                        "profile-1"
                )
        );
        when(ingestionPort.addCanonicalKnowledge(document, internalKey))
                .thenReturn(replay);
        when(repository.markIngested(claim, 42L, 7, "profile-1"))
                .thenReturn(true);

        worker.process(claim);

        verify(ingestionPort).addCanonicalKnowledge(document, internalKey);
        verify(repository).markIngested(claim, 42L, 7, "profile-1");
        verify(metrics).replayRecovery();
        verify(metrics).ingested();
    }

    private CanonicalKnowledgeDocument document() {
        return new CanonicalKnowledgeDocument(
                1,
                "doc-recovery",
                "1",
                "Recovery",
                "ru",
                KnowledgeDomain.TECHNICAL,
                1L,
                new CanonicalKnowledgeDocument.Source(
                        CanonicalKnowledgeDocument.SourceType.FILE,
                        "file-recovery",
                        "1",
                        "recovery.pdf",
                        "application/pdf",
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        new CanonicalKnowledgeDocument.StorageReference(
                                "rustfs",
                                "knowledge",
                                "tenant/recovery.pdf",
                                "v1"
                        )
                ),
                new CanonicalKnowledgeDocument.Processing(
                        "pdf-parser",
                        "1.0",
                        Instant.parse("2026-10-09T00:00:00Z")
                ),
                List.of(new CanonicalKnowledgeDocument.Block(
                        "b1",
                        CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                        "Recovery knowledge.",
                        null,
                        1,
                        1,
                        List.of("Recovery"),
                        null
                )),
                Map.of()
        );
    }
}
