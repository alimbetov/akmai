package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kz.alimbetov.akmai.rag.learning.LearningSourceFingerprint;
import kz.alimbetov.akmai.rag.learning.RagLearningEvent;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RagPolicyCanaryOutcomeRecorder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RagPolicyCanaryOutcomeRecorder.class);

    private final CanaryRoutingObservationStore routingStore;
    private final RagPolicyCanaryObservationRepository repository;
    private final LearningSourceFingerprint sourceFingerprint;

    public RagPolicyCanaryOutcomeRecorder(
            CanaryRoutingObservationStore routingStore,
            RagPolicyCanaryObservationRepository repository,
            LearningSourceFingerprint sourceFingerprint
    ) {
        this.routingStore = routingStore;
        this.repository = repository;
        this.sourceFingerprint = sourceFingerprint;
    }

    public Optional<CanaryRoutingObservationStore.Decision> record(
            String requestId,
            RetrievalExecutionResult execution,
            RagLearningEvent.AnswerStatus answerStatus,
            RagLearningEvent.GroundingStatus groundingStatus,
            long totalLatencyMs
    ) {
        CanaryRoutingObservationStore.Decision decision =
                routingStore.consumeCurrent().orElse(null);
        if (decision == null || !decision.candidateWouldChangePlan()) {
            return Optional.empty();
        }
        try {
            UUID parsedRequestId = UUID.fromString(requestId);
            repository.save(new RagPolicyCanaryObservationRepository.Observation(
                    decision.policyVersion(),
                    parsedRequestId,
                    decision.cohort(),
                    sourceFingerprint.currentOperational(),
                    decision.queryClass(),
                    answerStatus == null ? "UNKNOWN" : answerStatus.name(),
                    groundingStatus == null ? "UNKNOWN" : groundingStatus.name(),
                    execution != null && execution.degraded(),
                    execution != null && execution.criticalFailure(),
                    Math.max(0, totalLatencyMs),
                    Instant.now()
            ));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "rag_canary event=outcome_record_failed policy={} errorType={}",
                    decision.policyVersion(),
                    exception.getClass().getSimpleName()
            );
        }
        return Optional.of(decision);
    }
}
