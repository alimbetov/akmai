package kz.alimbetov.akmai.knowledge.ingestion.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.knowledge.api.AsyncIngestionAcceptedResponse;
import kz.alimbetov.akmai.knowledge.api.AsyncIngestionRequest;
import kz.alimbetov.akmai.knowledge.api.AsyncIngestionStatusResponse;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import org.springframework.stereotype.Service;

@Service
public class AsyncIngestionAdmissionService {

    private final AsyncIngestionJobRepository repository;
    private final AsyncIngestionJobFingerprint jobFingerprint;
    private final CanonicalRequestFingerprint canonicalFingerprint;
    private final AsyncIngestionProperties properties;
    private final ObjectMapper objectMapper;

    public AsyncIngestionAdmissionService(
            AsyncIngestionJobRepository repository,
            AsyncIngestionJobFingerprint jobFingerprint,
            CanonicalRequestFingerprint canonicalFingerprint,
            AsyncIngestionProperties properties,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.jobFingerprint = jobFingerprint;
        this.canonicalFingerprint = canonicalFingerprint;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AsyncIngestionAcceptedResponse admit(AsyncIngestionRequest request) {
        validate(request);
        CanonicalKnowledgeDocument document = request.canonicalDocument();
        String canonicalHash = canonicalFingerprint.canonicalHash(document);
        String fingerprint = jobFingerprint.fingerprint(
                request.schemaVersion(),
                request.documentId(),
                request.accessLevel(),
                document,
                canonicalHash
        );
        String payload = serialize(document);
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > properties.payloadMaxInlineBytes()) {
            throw new IllegalArgumentException(
                    "canonicalDocument exceeds async ingestion inline payload limit"
            );
        }

        UUID ingestionId = UUID.randomUUID();
        AsyncIngestionJob candidate = new AsyncIngestionJob(
                ingestionId,
                request.schemaVersion(),
                request.eventId().trim(),
                normalizeOptional(request.requestId()),
                fingerprint,
                "async-ingestion:" + ingestionId,
                request.documentId().trim(),
                request.accessLevel(),
                document.source().type().name(),
                document.source().fileId(),
                document.source().sourceVersion(),
                document.source().contentHash(),
                canonicalHash,
                "INLINE",
                payload,
                null,
                AsyncIngestionJobStatus.ACCEPTED,
                0,
                0,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        AsyncIngestionJob accepted = repository.admit(candidate);
        return acceptedResponse(accepted);
    }

    public StatusView statusView(UUID ingestionId) {
        AsyncIngestionJob job = requireJob(ingestionId);
        return new StatusView(job.accessLevel(), statusResponse(job));
    }

    private AsyncIngestionJob requireJob(UUID ingestionId) {
        return repository.find(ingestionId).orElseThrow(() ->
                new AsyncIngestionNotFoundException(ingestionId));
    }

    private void validate(AsyncIngestionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("async ingestion request is required");
        }
        if (request.schemaVersion() != AsyncIngestionRequest.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported async ingestion schemaVersion: "
                            + request.schemaVersion()
            );
        }
        boundedIdentifier("eventId", request.eventId(), 200);
        if (request.requestId() != null && !request.requestId().isBlank()) {
            boundedIdentifier("requestId", request.requestId(), 200);
        }
        boundedIdentifier("documentId", request.documentId(), 100);
        if (request.accessLevel() <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
        CanonicalKnowledgeDocument document = request.canonicalDocument();
        if (document == null) {
            throw new IllegalArgumentException("canonicalDocument is required");
        }
        if (!request.documentId().trim().equals(document.documentId())) {
            throw new IllegalArgumentException(
                    "documentId must match canonicalDocument.documentId"
            );
        }
        if (request.accessLevel() != document.accessLevel()) {
            throw new IllegalArgumentException(
                    "accessLevel must match canonicalDocument.accessLevel"
            );
        }
    }

    private void boundedIdentifier(String name, String value, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum length " + maximum
            );
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must not contain control characters"
            );
        }
    }

    private String serialize(CanonicalKnowledgeDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "canonicalDocument is not serializable JSON",
                    exception
            );
        }
    }

    private AsyncIngestionAcceptedResponse acceptedResponse(AsyncIngestionJob job) {
        return new AsyncIngestionAcceptedResponse(
                AsyncIngestionAcceptedResponse.CURRENT_SCHEMA_VERSION,
                job.ingestionId(),
                job.documentId(),
                job.status().name()
        );
    }

    private AsyncIngestionStatusResponse statusResponse(AsyncIngestionJob job) {
        AsyncIngestionStatusResponse.Publication publication =
                job.generation() == null
                        ? null
                        : new AsyncIngestionStatusResponse.Publication(
                                job.generation(),
                                job.chunkCount(),
                                job.embeddingProfileId()
                        );
        AsyncIngestionStatusResponse.Error error =
                job.lastErrorClass() == null
                        && job.lastErrorCode() == null
                        ? null
                        : new AsyncIngestionStatusResponse.Error(
                                job.lastErrorClass(),
                                job.lastErrorCode()
                        );
        return new AsyncIngestionStatusResponse(
                AsyncIngestionStatusResponse.CURRENT_SCHEMA_VERSION,
                job.ingestionId(),
                job.documentId(),
                job.status().name(),
                job.attemptCount(),
                job.failureCount(),
                publication,
                error,
                job.acceptedAt(),
                job.startedAt(),
                job.finishedAt()
        );
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record StatusView(
            long accessLevel,
            AsyncIngestionStatusResponse response
    ) {
    }
}
