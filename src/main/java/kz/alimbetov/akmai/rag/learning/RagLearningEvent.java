package kz.alimbetov.akmai.rag.learning;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record RagLearningEvent(
        UUID eventId,
        UUID requestId,
        String queryFingerprint,
        Set<Long> accessLevels,
        String language,
        String queryClass,
        String corpusVersion,
        String embeddingProfileId,
        String retrievalPolicyVersion,
        String learningPolicyVersion,
        String groundingPolicyVersion,
        AnswerStatus answerStatus,
        GroundingStatus groundingStatus,
        int retrievedCount,
        int selectedCount,
        int citedCount,
        long totalLatencyMs,
        Map<String, Object> trace,
        Instant createdAt
) {
    public RagLearningEvent {
        accessLevels = accessLevels == null ? Set.of() : Set.copyOf(accessLevels);
        trace = trace == null ? Map.of() : Map.copyOf(trace);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public enum AnswerStatus {
        GROUNDED,
        INSUFFICIENT,
        UNGROUNDED,
        UNAVAILABLE
    }

    public enum GroundingStatus {
        SUPPORTED,
        CONTRADICTED,
        INSUFFICIENT,
        DETERMINISTIC_REJECTED,
        NOT_EVALUATED
    }
}
