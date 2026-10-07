package kz.alimbetov.akmai.rag.learning;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import kz.alimbetov.akmai.rag.policy.RetrievalPolicyShadowEvaluator;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import kz.alimbetov.akmai.rag.trace.RagExecutionObservationStore;
import kz.alimbetov.akmai.rag.trace.RagExecutionTrace;
import kz.alimbetov.akmai.rag.trace.RagRuntimeAttribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RagLearningRecorder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RagLearningRecorder.class);

    private final RagLearningEventRepository repository;
    private final LearningPrivacyFingerprint fingerprint;
    private final SelfOptimizingRagProperties properties;
    private final AdaptiveRetrievalPlanner adaptiveRetrievalPlanner;
    private final RagRuntimeAttribution runtimeAttribution;
    private final RagExecutionObservationStore observationStore;
    private final LearningSourceFingerprint sourceFingerprint;
    private final RetrievalPolicyShadowEvaluator shadowEvaluator;

    public RagLearningRecorder(
            RagLearningEventRepository repository,
            LearningPrivacyFingerprint fingerprint,
            SelfOptimizingRagProperties properties,
            AdaptiveRetrievalPlanner adaptiveRetrievalPlanner,
            RagRuntimeAttribution runtimeAttribution,
            RagExecutionObservationStore observationStore
    ) {
        this(
                repository,
                fingerprint,
                properties,
                adaptiveRetrievalPlanner,
                runtimeAttribution,
                observationStore,
                null,
                null
        );
    }

    public RagLearningRecorder(
            RagLearningEventRepository repository,
            LearningPrivacyFingerprint fingerprint,
            SelfOptimizingRagProperties properties,
            AdaptiveRetrievalPlanner adaptiveRetrievalPlanner,
            RagRuntimeAttribution runtimeAttribution,
            RagExecutionObservationStore observationStore,
            LearningSourceFingerprint sourceFingerprint
    ) {
        this(
                repository,
                fingerprint,
                properties,
                adaptiveRetrievalPlanner,
                runtimeAttribution,
                observationStore,
                sourceFingerprint,
                null
        );
    }

    @Autowired
    public RagLearningRecorder(
            RagLearningEventRepository repository,
            LearningPrivacyFingerprint fingerprint,
            SelfOptimizingRagProperties properties,
            AdaptiveRetrievalPlanner adaptiveRetrievalPlanner,
            RagRuntimeAttribution runtimeAttribution,
            RagExecutionObservationStore observationStore,
            LearningSourceFingerprint sourceFingerprint,
            RetrievalPolicyShadowEvaluator shadowEvaluator
    ) {
        this.repository = repository;
        this.fingerprint = fingerprint;
        this.properties = properties;
        this.adaptiveRetrievalPlanner = adaptiveRetrievalPlanner;
        this.runtimeAttribution = runtimeAttribution;
        this.observationStore = observationStore;
        this.sourceFingerprint = sourceFingerprint;
        this.shadowEvaluator = shadowEvaluator;
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
        boolean persistentLearning = properties.learningEventsEnabled();
        boolean executionObservation = properties.executionObservationsEnabled();
        boolean grounded = answerStatus == RagLearningEvent.AnswerStatus.GROUNDED
                && groundingStatus == RagLearningEvent.GroundingStatus.SUPPORTED;
        if (!persistentLearning
                && !executionObservation
                && (!grounded || shadowEvaluator == null)) {
            return;
        }
        try {
            UUID parsedRequestId = UUID.fromString(requestId);
            String language = language(queryChunks);
            String queryClass = queryClass(queryChunks);
            RagRuntimeAttribution.Snapshot attribution = runtimeAttribution.snapshot();
            int retrievedCount = execution == null ? 0 : execution.hits().size();
            int selectedCount = finalContext == null ? 0 : finalContext.size();
            int citedCount = validation == null || validation.citedSources() == null
                    ? 0
                    : validation.citedSources().size();

            RagExecutionTrace trace = new RagExecutionTrace(
                    requestId,
                    attribution.corpusVersion(),
                    attribution.embeddingProfileId(),
                    attribution.retrievalPolicyVersion(),
                    attribution.learningPolicyVersion(),
                    attribution.groundingPolicyVersion(),
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
            if (executionObservation && observationStore != null) {
                observationStore.record(
                        trace,
                        finalContext,
                        validation,
                        attribution
                );
            }

            if (grounded && shadowEvaluator != null) {
                shadowEvaluator.observeGrounded(
                        question,
                        queryChunks,
                        accessLevels,
                        finalContext,
                        validation
                );
            }

            if (!persistentLearning) {
                return;
            }
            String queryFingerprint = fingerprint.fingerprint(question);
            if (queryFingerprint.isBlank()) {
                return;
            }
            LinkedHashMap<String, Object> tracePayload = new LinkedHashMap<>(
                    trace.learningProjection()
            );
            tracePayload.put("attribution", attribution.asMap());
            String source = sourceFingerprint == null
                    ? ""
                    : sourceFingerprint.current();
            if (!source.isBlank()) {
                tracePayload.put("sourceFingerprint", source);
            }

            RagLearningEvent event = new RagLearningEvent(
                    UUID.randomUUID(),
                    parsedRequestId,
                    queryFingerprint,
                    accessLevels,
                    language,
                    queryClass,
                    attribution.corpusVersion(),
                    attribution.embeddingProfileId(),
                    attribution.retrievalPolicyVersion(),
                    attribution.learningPolicyVersion(),
                    attribution.groundingPolicyVersion(),
                    answerStatus,
                    groundingStatus,
                    retrievedCount,
                    selectedCount,
                    citedCount,
                    Math.max(0, totalLatencyMs),
                    Map.copyOf(tracePayload),
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
        try {
            return adaptiveRetrievalPlanner.classifyPrimary(queryChunks).name();
        } catch (RuntimeException exception) {
            return AdaptiveRetrievalPlanner.QueryClass.ANALYSIS_UNAVAILABLE.name();
        }
    }
}
