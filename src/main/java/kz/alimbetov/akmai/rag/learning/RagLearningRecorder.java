package kz.alimbetov.akmai.rag.learning;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.rag.policy.ApprovedRetrievalPolicyProvider;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.trace.RagExecutionTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RagLearningRecorder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RagLearningRecorder.class);

    private final RagLearningEventRepository repository;
    private final LearningPrivacyFingerprint fingerprint;
    private final SelfOptimizingRagProperties properties;
    private final EmbeddingProfileService embeddingProfileService;
    private final ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider;

    public RagLearningRecorder(
            RagLearningEventRepository repository,
            LearningPrivacyFingerprint fingerprint,
            SelfOptimizingRagProperties properties,
            EmbeddingProfileService embeddingProfileService,
            ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider
    ) {
        this.repository = repository;
        this.fingerprint = fingerprint;
        this.properties = properties;
        this.embeddingProfileService = embeddingProfileService;
        this.approvedRetrievalPolicyProvider = approvedRetrievalPolicyProvider;
    }

    public void record(
            String requestId,
            String question,
            List<QueryChunk> queryChunks,
            Set<Long> accessLevels,
            RetrievalExecutionResult execution,
            List<RetrievalHit> finalContext,
            CitationValidator.CitationValidation validation,
            RagLearningEvent.AnswerStatus answerStatus,
            RagLearningEvent.GroundingStatus groundingStatus,
            long totalLatencyMs
    ) {
        if (!properties.learningEventsEnabled()) {
            return;
        }
        try {
            UUID parsedRequestId = UUID.fromString(requestId);
            String queryFingerprint = fingerprint.fingerprint(question);
            if (queryFingerprint.isBlank()) {
                return;
            }
            String language = language(queryChunks);
            String queryClass = queryClass(queryChunks);
            String embeddingProfile = activeEmbeddingProfile();
            String retrievalPolicyVersion = approvedRetrievalPolicyProvider
                    .approvedVersion()
                    .orElse(properties.retrievalPolicyVersion());
            int retrievedCount = execution == null ? 0 : execution.hits().size();
            int selectedCount = finalContext == null ? 0 : finalContext.size();
            int citedCount = validation == null || validation.citedSources() == null
                    ? 0
                    : validation.citedSources().size();

            RagExecutionTrace trace = new RagExecutionTrace(
                    requestId,
                    properties.corpusVersion(),
                    embeddingProfile,
                    retrievalPolicyVersion,
                    properties.learningPolicyVersion(),
                    properties.groundingPolicyVersion(),
                    language,
                    queryClass,
                    execution != null && execution.degraded(),
                    execution != null && execution.criticalFailure(),
                    RagExecutionTrace.laneOutcomes(execution),
                    RagExecutionTrace.laneContributions(finalContext),
                    RagExecutionTrace.citedLaneContributions(
                            finalContext,
                            validation
                    ),
                    retrievedCount,
                    selectedCount,
                    citedCount,
                    answerStatus,
                    groundingStatus,
                    totalLatencyMs
            );

            RagLearningEvent event = new RagLearningEvent(
                    UUID.randomUUID(),
                    parsedRequestId,
                    queryFingerprint,
                    accessLevels,
                    language,
                    queryClass,
                    properties.corpusVersion(),
                    embeddingProfile,
                    retrievalPolicyVersion,
                    properties.learningPolicyVersion(),
                    properties.groundingPolicyVersion(),
                    answerStatus,
                    groundingStatus,
                    retrievedCount,
                    selectedCount,
                    citedCount,
                    Math.max(0, totalLatencyMs),
                    trace.learningProjection(),
                    Instant.now()
            );
            repository.save(event);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "rag_learning event=record_failed errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String activeEmbeddingProfile() {
        try {
            return embeddingProfileService.activeProfile().profileId();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String language(List<QueryChunk> queryChunks) {
        if (queryChunks == null || queryChunks.isEmpty()) {
            return "unknown";
        }
        String selected = null;
        for (QueryChunk chunk : queryChunks) {
            if (chunk == null || chunk.language() == null || chunk.language().isBlank()) {
                continue;
            }
            if (selected == null) {
                selected = chunk.language();
            } else if (!selected.equalsIgnoreCase(chunk.language())) {
                return "mixed";
            }
        }
        return selected == null ? "unknown" : selected;
    }

    private String queryClass(List<QueryChunk> queryChunks) {
        if (queryChunks == null || queryChunks.isEmpty()) {
            return "UNKNOWN";
        }
        boolean identifier = queryChunks.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(chunk -> chunk.identifiers() != null
                        && !chunk.identifiers().isEmpty());
        boolean semantic = queryChunks.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(chunk -> chunk.semanticText() != null
                        && !chunk.semanticText().isBlank());
        if (identifier && semantic) {
            return "IDENTIFIER_SEMANTIC";
        }
        if (identifier) {
            return "IDENTIFIER_ONLY";
        }
        return semantic ? "GENERIC" : "UNKNOWN";
    }
}
