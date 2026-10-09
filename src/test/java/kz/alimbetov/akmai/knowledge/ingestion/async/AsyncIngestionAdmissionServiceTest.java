package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.knowledge.api.AsyncIngestionRequest;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AsyncIngestionAdmissionServiceTest {

    AsyncIngestionJobRepository repository;
    AsyncIngestionAdmissionService service;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(AsyncIngestionJobRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        AsyncIngestionProperties properties = new AsyncIngestionProperties(
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
        service = new AsyncIngestionAdmissionService(
                repository,
                new AsyncIngestionJobFingerprint(),
                new CanonicalRequestFingerprint(objectMapper),
                properties,
                objectMapper
        );
        when(repository.admit(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void admissionPersistsStableIdentityAndInlineCanonicalSnapshot() throws Exception {
        CanonicalKnowledgeDocument document = document();
        AsyncIngestionRequest request = new AsyncIngestionRequest(
                1,
                "event-1",
                "request-1",
                document.documentId(),
                document.accessLevel(),
                document
        );

        var response = service.admit(request);

        ArgumentCaptor<AsyncIngestionJob> captor =
                ArgumentCaptor.forClass(AsyncIngestionJob.class);
        verify(repository).admit(captor.capture());
        AsyncIngestionJob job = captor.getValue();

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.ingestionId()).isEqualTo(job.ingestionId());
        assertThat(job.internalIdempotencyKey())
                .isEqualTo("async-ingestion:" + job.ingestionId());
        assertThat(job.eventId()).isEqualTo("event-1");
        assertThat(job.fileId()).isEqualTo("file-1");
        assertThat(job.sourceVersion()).isEqualTo("1");
        assertThat(job.payloadMode()).isEqualTo("INLINE");
        assertThat(objectMapper.readValue(
                job.payloadJson(),
                CanonicalKnowledgeDocument.class
        )).isEqualTo(document);
        assertThat(job.canonicalHash()).hasSize(64);
        assertThat(job.jobFingerprint()).hasSize(64);
    }

    @Test
    void envelopeIdentityMustMatchCanonicalPayload() {
        CanonicalKnowledgeDocument document = document();
        AsyncIngestionRequest request = new AsyncIngestionRequest(
                1,
                "event-1",
                null,
                "different-document",
                document.accessLevel(),
                document
        );

        assertThatThrownBy(() -> service.admit(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId must match");
    }

    @Test
    void inlinePayloadBoundIsEnforcedBeforeDurableAcceptance() {
        AsyncIngestionProperties tiny = new AsyncIngestionProperties(
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
                1024
        );
        service = new AsyncIngestionAdmissionService(
                repository,
                new AsyncIngestionJobFingerprint(),
                new CanonicalRequestFingerprint(objectMapper),
                tiny,
                objectMapper
        );
        CanonicalKnowledgeDocument document = new CanonicalKnowledgeDocument(
                1,
                "doc-large",
                "1",
                "Title",
                "ru",
                KnowledgeDomain.TECHNICAL,
                1L,
                document().source(),
                document().processing(),
                List.of(new CanonicalKnowledgeDocument.Block(
                        "b-large",
                        CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                        "x".repeat(5000),
                        null,
                        1,
                        1,
                        List.of("Section"),
                        null
                )),
                Map.of()
        );

        assertThatThrownBy(() -> service.admit(new AsyncIngestionRequest(
                1,
                "event-large",
                null,
                document.documentId(),
                document.accessLevel(),
                document
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inline payload limit");
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
}
