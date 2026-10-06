package kz.alimbetov.akmai.rag.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import kz.alimbetov.akmai.rag.learning.RagLearningEvent;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;

public record RagExecutionTrace(
        String requestId,
        String corpusVersion,
        String embeddingProfileId,
        String retrievalPolicyVersion,
        String learningPolicyVersion,
        String groundingPolicyVersion,
        String language,
        String queryClass,
        boolean retrievalDegraded,
        boolean retrievalCriticalFailure,
        Map<String, Integer> laneOutcomes,
        int retrievedCount,
        int selectedCount,
        int citedCount,
        RagLearningEvent.AnswerStatus answerStatus,
        RagLearningEvent.GroundingStatus groundingStatus,
        long totalLatencyMs
) {
    public RagExecutionTrace {
        laneOutcomes = laneOutcomes == null ? Map.of() : Map.copyOf(laneOutcomes);
        totalLatencyMs = Math.max(0, totalLatencyMs);
        retrievedCount = Math.max(0, retrievedCount);
        selectedCount = Math.max(0, selectedCount);
        citedCount = Math.max(0, citedCount);
    }

    public static Map<String, Integer> laneOutcomes(
            RetrievalExecutionResult execution
    ) {
        if (execution == null || execution.outcomes() == null) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        execution.outcomes().values().forEach(outcome -> {
            String type = outcome.type() == null ? "UNKNOWN" : outcome.type().name();
            String status = outcome.status() == null
                    ? "UNKNOWN"
                    : outcome.status().name();
            result.merge(type + ":" + status, 1, Integer::sum);
        });
        return Map.copyOf(result);
    }

    public Map<String, Object> learningProjection() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("retrievalDegraded", retrievalDegraded);
        result.put("retrievalCriticalFailure", retrievalCriticalFailure);
        result.put("laneOutcomes", laneOutcomes);
        result.put("retrievedCount", retrievedCount);
        result.put("selectedCount", selectedCount);
        result.put("citedCount", citedCount);
        result.put("totalLatencyMs", totalLatencyMs);
        return Map.copyOf(result);
    }
}
