package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncIngestionWorkerTest {

    KnowledgeIngestionPort ingestionPort;
    AsyncIngestionJobRepository repository;
    ObjectMapper objectMapper;
    CanonicalRequestFingerprint fingerprint;
    AsyncIngestionProperties properties;
    AsyncIngestionWorker worker;

    @BeforeEach
    void setUp() {
        ingestionPort = org.mockito.Mockito.mock(KnowledgeIngestionPort.class);
        repository = org.mockito.Mockito.mock(AsyncIngestionJobRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        fingerprint = new CanonicalRequestFingerprint(objectMapper);
        properties = new AsyncIngestionProperties(
                false,
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
        worker = new AsyncIngestionWorker(
                ingestionPort,
                repository,
                heartbeat,
                new AsyncIngestionFailureClassifier(),
                properties,
                fingerprint,
                objectMapper
        );
    }

    @Test
    void successfulIngestionFinalizesPublishedResult() throws Exception {
        Fixture fixture = fixture();
        KnowledgeIngestionResult result = result(fixture.document());
        when(ingestionPort.addCanonicalKnowledge(
                fixture.document(),
                fixture.job().internalIdempotencyKey()
        )).thenReturn(result);
        when(repository.markIngested(
                fixture.claim(),
                7L,
                2,
                "profile-1"
        )).thenReturn(true);

        worker.process(fixture.claim());

        verify(repository).markIngested(
                fixture.claim(),
                7L,
                2,
                "profile-1"
        );
        verify(repository, never()).markRetry(
                any(), any(), eq(true), any(), any(), any()
        );
        verify(repository, never()).markFailed(
                any(), any(), any(), any()
        );
    }

    @Test
    void inProgressMovesToRetryWithoutConsumingFailureBudget() throws Exception {
        Fixture fixture = fixture();
        when(ingestionPort.addCanonicalKnowledge(
                fixture.document(),
                fixture.job().internalIdempotencyKey()
        )).thenThrow(new IdempotencyConflictException(
                "INGESTION_IN_PROGRESS",
                "still running",
                30L
        ));
        when(repository.markRetry(
                eq(fixture.claim()),
                any(),
                eq(false),
                eq("RETRYABLE"),
                eq("INGESTION_IN_PROGRESS"),
                eq("still running")
        )).thenReturn(true);

        worker.process(fixture.claim());

        verify(repository).markRetry(
                eq(fixture.claim()),
                any(),
                eq(false),
                eq("RETRYABLE"),
                eq("INGESTION_IN_PROGRESS"),
                eq("still running")
        );
        verify(repository, never()).markFailed(
                any(), any(), any(), any()
        );
    }

    @Test
    void corruptedDurablePayloadFailsWithoutCallingRagPipeline() throws Exception {
        Fixture fixture = fixture();
        AsyncIngestionJob corrupted = new AsyncIngestionJob(
                fixture.job().ingestionId(),
                fixture.job().schemaVersion(),
                fixture.job().eventId(),
                fixture.job().requestId(),
                fixture.job().jobFingerprint(),
                fixture.job().internalIdempotencyKey(),
                fixture.job().documentId(),
                fixture.job().accessLevel(),
                fixture.job().sourceType(),
                fixture.job().fileId(),
                fixture.job().sourceVersion(),
                fixture.job().contentHash(),
                "deadbeef",
                fixture.job().payloadMode(),
                fixture.job().payloadJson(),
                fixture.job().artifactId(),
                fixture.job().status(),
                fixture.job().attemptCount(),
                fixture.job().failureCount(),
                fixture.job().nextAttemptAt(),
                fixture.job().leaseOwner(),
                fixture.job().leaseUntil(),
                fixture.job().leaseVersion(),
                fixture.job().generation(),
                fixture.job().chunkCount(),
                fixture.job().embeddingProfileId(),
                fixture.job().lastErrorClass(),
                fixture.job().lastErrorCode(),
                fixture.job().lastErrorMessage(),
                fixture.job().acceptedAt(),
                fixture.job().startedAt(),
                fixture.job().finishedAt(),
                fixture.job().createdAt(),
                fixture.job().updatedAt()
        );
        AsyncIngestionClaim claim = new AsyncIngestionClaim(
                corrupted.ingestionId(),
                "worker-1",
                1L,
                corrupted
        );
        when(repository.markFailed(
                eq(claim),
                eq("NON_RETRYABLE"),
                eq("VALIDATION_ERROR"),
                any()
        )).thenReturn(true);

        worker.process(claim);

        verify(ingestionPort, never()).addCanonicalKnowledge(any(), any());
        verify(repository).markFailed(
                eq(claim),
                eq("NON_RETRYABLE"),
                eq("VALIDATION_ERROR"),
                any()
        );
    }

    private Fixture fixture() throws Exception {
        CanonicalKnowledgeDocument document = document();
        String canonicalHash = fingerprint.canonicalHash(document);
        UUID ingestionId = UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(document);
        AsyncIngestionJob job = new AsyncIngestionJob(
                ingestionId,
                1,
                "event-1",
                null,
                "job-fingerprint",
                "async-ingestion:" + ingestionId,
                document.documentId(),
                document.accessLevel(),
                document.source().type().name(),
                document.source().fileId(),
                document.source().sourceVersion(),
                document.source().contentHash(),
                canonicalHash,
                "INLINE",
                payload,
                null,
                AsyncIngestionJobStatus.PROCESSING,
                1,
                0,
                null,
                "worker-1",
                Instant.now().plusSeconds(120),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );
        AsyncIngestionClaim claim = new AsyncIngestionClaim(
                ingestionId,
                "worker-1",
                1L,
                job
        );
        return new Fixture(document, job, claim);
    }

    private CanonicalKnowledgeDocument document() {
        return new CanonicalKnowledgeDocument(
                1,
                "doc-1",
                "1",
                "Title",
                "ru",
                KnowledgeDomain.TECHNICAL,
                1L,
                new CanonicalKnowledgeDocument.Source(
                        CanonicalKnowledgeDocument.SourceType.FILE,
                        "file-1",
                        "1",
                        "doc.pdf",
                        "application/pdf",
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        new CanonicalKnowledgeDocument.StorageReference(
                                "rustfs",
                                "knowledge",
                                "tenant/doc.pdf",
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
                        "Useful knowledge text.",
                        null,
                        1,
                        1,
                        List.of("Section"),
                        null
                )),
                Map.of()
        );
    }

    private KnowledgeIngestionResult result(CanonicalKnowledgeDocument document) {
        return new KnowledgeIngestionResult(
                1,
                document.documentId(),
                new KnowledgeIngestionResult.Source(
                        "FILE",
                        document.source().fileId(),
                        document.source().sourceVersion(),
                        document.source().contentHash()
                ),
                new KnowledgeIngestionResult.Publication(
                        KnowledgeIngestionResult.PublicationStatus.PUBLISHED,
                        7L,
                        2
                ),
                new KnowledgeIngestionResult.Processing(
                        1,
                        fingerprint.canonicalHash(document),
                        "pdf-parser",
                        "1.0",
                        "profile-1"
                )
        );
    }

    private record Fixture(
            CanonicalKnowledgeDocument document,
            AsyncIngestionJob job,
            AsyncIngestionClaim claim
    ) {
    }
}
