package kz.alimbetov.akmai.knowledge.ingestion.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import kz.alimbetov.akmai.config.AsyncIngestionProperties;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResult;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionPort;
import kz.alimbetov.akmai.observability.AsyncIngestionMetrics;
import org.springframework.stereotype.Component;

@Component
public class AsyncIngestionWorker {

    private final KnowledgeIngestionPort ingestionPort;
    private final AsyncIngestionJobRepository repository;
    private final AsyncIngestionHeartbeat heartbeat;
    private final AsyncIngestionFailureClassifier failureClassifier;
    private final AsyncIngestionProperties properties;
    private final CanonicalRequestFingerprint canonicalFingerprint;
    private final ObjectMapper objectMapper;
    private final AsyncIngestionMetrics metrics;

    public AsyncIngestionWorker(
            KnowledgeIngestionPort ingestionPort,
            AsyncIngestionJobRepository repository,
            AsyncIngestionHeartbeat heartbeat,
            AsyncIngestionFailureClassifier failureClassifier,
            AsyncIngestionProperties properties,
            CanonicalRequestFingerprint canonicalFingerprint,
            ObjectMapper objectMapper,
            AsyncIngestionMetrics metrics
    ) {
        this.ingestionPort = ingestionPort;
        this.repository = repository;
        this.heartbeat = heartbeat;
        this.failureClassifier = failureClassifier;
        this.properties = properties;
        this.canonicalFingerprint = canonicalFingerprint;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public void process(AsyncIngestionClaim claim) {
        Instant startedAt = Instant.now();
        metrics.processingStarted();
        String outcome = "failed";
        try (AsyncIngestionHeartbeat.Handle lease = heartbeat.register(claim)) {
            try {
                CanonicalKnowledgeDocument document = loadAndValidate(claim.job());
                KnowledgeIngestionResult result = ingestionPort.addCanonicalKnowledge(
                        document,
                        claim.job().internalIdempotencyKey()
                );
                if (lease.ownershipLost()) {
                    metrics.leaseLost();
                    outcome = "lease_lost";
                    return;
                }
                validateResult(claim.job(), result);
                if (repository.markIngested(
                        claim,
                        result.publication().generation(),
                        result.publication().chunkCount(),
                        result.processing().embeddingProfile()
                )) {
                    metrics.ingested();
                    if (result.publication().status()
                            == KnowledgeIngestionResult.PublicationStatus.REPLAYED) {
                        metrics.replayRecovery();
                    }
                    outcome = "ingested";
                } else {
                    metrics.leaseLost();
                    outcome = "lease_lost";
                }
            } catch (RuntimeException exception) {
                if (lease.ownershipLost()) {
                    metrics.leaseLost();
                    outcome = "lease_lost";
                    return;
                }
                outcome = handleFailure(claim, exception);
            }
        } finally {
            metrics.processingFinished();
            metrics.processing(
                    outcome,
                    Duration.between(startedAt, Instant.now())
            );
        }
    }

    private CanonicalKnowledgeDocument loadAndValidate(AsyncIngestionJob job) {
        if (!"INLINE".equals(job.payloadMode())) {
            throw new IllegalArgumentException(
                    "ARTIFACT_REF async ingestion is not enabled in v1"
            );
        }
        if (job.payloadJson() == null || job.payloadJson().isBlank()) {
            throw new IllegalArgumentException("async ingestion payload is missing");
        }
        CanonicalKnowledgeDocument document;
        try {
            document = objectMapper.readValue(
                    job.payloadJson(),
                    CanonicalKnowledgeDocument.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "async ingestion payload is not a valid CanonicalKnowledgeDocument",
                    exception
            );
        }

        if (!job.documentId().equals(document.documentId())) {
            throw new IllegalArgumentException(
                    "durable async payload documentId does not match job identity"
            );
        }
        if (job.accessLevel() != document.accessLevel()) {
            throw new IllegalArgumentException(
                    "durable async payload accessLevel does not match job identity"
            );
        }
        if (!job.sourceType().equals(document.source().type().name())
                || !equals(job.fileId(), document.source().fileId())
                || !job.sourceVersion().equals(document.source().sourceVersion())
                || !equals(job.contentHash(), document.source().contentHash())) {
            throw new IllegalArgumentException(
                    "durable async payload source identity does not match job identity"
            );
        }
        String canonicalHash = canonicalFingerprint.canonicalHash(document);
        if (!canonicalHash.equals(job.canonicalHash())) {
            throw new IllegalArgumentException(
                    "durable async payload canonical hash does not match admission hash"
            );
        }
        return document;
    }

    private void validateResult(
            AsyncIngestionJob job,
            KnowledgeIngestionResult result
    ) {
        if (result == null) {
            throw new IllegalStateException(
                    "Knowledge ingestion returned no result"
            );
        }
        if (!job.documentId().equals(result.documentId())) {
            throw new IllegalStateException(
                    "Knowledge ingestion result documentId does not match async job"
            );
        }
        if (result.publication().generation() <= 0
                || result.publication().chunkCount() <= 0
                || result.processing().embeddingProfile() == null
                || result.processing().embeddingProfile().isBlank()) {
            throw new IllegalStateException(
                    "Knowledge ingestion result is missing publication identity"
            );
        }
    }

    private String handleFailure(
            AsyncIngestionClaim claim,
            RuntimeException exception
    ) {
        AsyncIngestionFailureClassifier.Failure failure =
                failureClassifier.classify(exception);
        int consumedFailures = claim.job().failureCount()
                + (failure.consumesFailureBudget() ? 1 : 0);

        if (failure.classification()
                == AsyncIngestionFailureClassifier.Classification.AMBIGUOUS) {
            metrics.ambiguous();
        }

        if (!failure.retryable()
                || (failure.consumesFailureBudget()
                    && consumedFailures >= properties.maxAttempts())) {
            if (repository.markFailed(
                    claim,
                    failure.classification().name(),
                    failure.code(),
                    exception.getMessage()
            )) {
                metrics.failed();
                return "failed";
            }
            metrics.leaseLost();
            return "lease_lost";
        }

        Duration delay = failure.suggestedDelay() == null
                ? backoff(consumedFailures)
                : bounded(failure.suggestedDelay());
        if (repository.markRetry(
                claim,
                Instant.now().plus(delay),
                failure.consumesFailureBudget(),
                failure.classification().name(),
                failure.code(),
                exception.getMessage()
        )) {
            metrics.retry();
            return "retry";
        }
        metrics.leaseLost();
        return "lease_lost";
    }

    private Duration backoff(int consumedFailures) {
        long baseMillis = properties.retryBaseDelay().toMillis();
        int exponent = Math.max(0, Math.min(consumedFailures - 1, 20));
        long multiplier = 1L << exponent;
        long unbounded;
        try {
            unbounded = Math.multiplyExact(baseMillis, multiplier);
        } catch (ArithmeticException exception) {
            unbounded = Long.MAX_VALUE;
        }
        long capped = Math.min(
                unbounded,
                properties.retryMaxDelay().toMillis()
        );
        double jitter = ThreadLocalRandom.current().nextDouble(0.8d, 1.2d);
        long jittered = Math.max(1L, Math.round(capped * jitter));
        return Duration.ofMillis(Math.min(
                jittered,
                properties.retryMaxDelay().toMillis()
        ));
    }

    private Duration bounded(Duration delay) {
        if (delay.isNegative() || delay.isZero()) {
            return properties.retryBaseDelay();
        }
        if (delay.compareTo(properties.retryMaxDelay()) > 0) {
            return properties.retryMaxDelay();
        }
        return delay;
    }

    private boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
