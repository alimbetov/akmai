package kz.alimbetov.akmai.rag.learning;

import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class RagFeedbackService {

    private final RagLearningEventRepository learningEventRepository;
    private final RagFeedbackRepository feedbackRepository;
    private final LearningSourceFingerprint sourceFingerprint;

    public RagFeedbackService(
            RagLearningEventRepository learningEventRepository,
            RagFeedbackRepository feedbackRepository
    ) {
        this(learningEventRepository, feedbackRepository, null);
    }

    @Autowired
    public RagFeedbackService(
            RagLearningEventRepository learningEventRepository,
            RagFeedbackRepository feedbackRepository,
            LearningSourceFingerprint sourceFingerprint
    ) {
        this.learningEventRepository = learningEventRepository;
        this.feedbackRepository = feedbackRepository;
        this.sourceFingerprint = sourceFingerprint;
    }

    public RagFeedbackRepository.Result record(
            String requestId,
            RagFeedbackReason reason,
            String details,
            Set<Long> effectiveAccessLevels,
            String idempotencyKey
    ) {
        UUID parsedRequestId;
        try {
            parsedRequestId = UUID.fromString(requestId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("requestId must be a UUID", exception);
        }

        Set<Long> eventScope = learningEventRepository
                .findAccessLevels(parsedRequestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown RAG requestId"
                ));
        if (effectiveAccessLevels == null
                || effectiveAccessLevels.isEmpty()
                || !effectiveAccessLevels.containsAll(eventScope)) {
            throw new AccessDeniedException(
                    "Feedback request is outside the effective access scope"
            );
        }
        String normalizedDetails = details == null || details.isBlank()
                ? null
                : details.trim();
        return feedbackRepository.record(
                idempotencyKey,
                parsedRequestId,
                reason,
                normalizedDetails,
                RagFeedbackTrustClass.USER_UNVERIFIED,
                sourceFingerprint == null ? null : sourceFingerprint.current()
        );
    }
}
