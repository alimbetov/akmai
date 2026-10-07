package kz.alimbetov.akmai.rag.trace;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.rag.learning.RagLearningEvent;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;

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
        Map<String, Integer> selectedLaneContributions,
        Map<String, Integer> citedLaneContributions,
        int retrievedCount,
        int selectedCount,
        int citedCount,
        RagLearningEvent.AnswerStatus answerStatus,
        RagLearningEvent.GroundingStatus groundingStatus,
        long totalLatencyMs
) {
    public RagExecutionTrace(
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
        this(
                requestId,
                corpusVersion,
                embeddingProfileId,
                retrievalPolicyVersion,
                learningPolicyVersion,
                groundingPolicyVersion,
                language,
                queryClass,
                retrievalDegraded,
                retrievalCriticalFailure,
                laneOutcomes,
                Map.of(),
                Map.of(),
                retrievedCount,
                selectedCount,
                citedCount,
                answerStatus,
                groundingStatus,
                totalLatencyMs
        );
    }

    public RagExecutionTrace {
        laneOutcomes = laneOutcomes == null ? Map.of() : Map.copyOf(laneOutcomes);
        selectedLaneContributions = selectedLaneContributions == null
                ? Map.of()
                : Map.copyOf(selectedLaneContributions);
        citedLaneContributions = citedLaneContributions == null
                ? Map.of()
                : Map.copyOf(citedLaneContributions);
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

    public static Map<String, Integer> laneContributions(
            List<RetrievalHit> hits
    ) {
        if (hits == null || hits.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (RetrievalHit hit : hits) {
            if (hit == null) {
                continue;
            }
            Set<RetrievalType> lanes = new LinkedHashSet<>();
            if (hit.evidence() != null) {
                hit.evidence().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(evidence -> evidence.type())
                        .filter(java.util.Objects::nonNull)
                        .forEach(lanes::add);
            }
            if (lanes.isEmpty() && hit.type() != null) {
                lanes.add(hit.type());
            }
            lanes.forEach(lane -> result.merge(lane.name(), 1, Integer::sum));
        }
        return Map.copyOf(result);
    }

    public static Map<String, Integer> citedLaneContributions(
            List<RetrievalHit> finalContext,
            CitationValidator.CitationValidation validation
    ) {
        if (finalContext == null
                || finalContext.isEmpty()
                || validation == null
                || validation.citedSources() == null
                || validation.citedSources().isEmpty()) {
            return Map.of();
        }
        List<RetrievalHit> cited = validation.citedSources().stream()
                .map(source -> source.number() - 1)
                .filter(index -> index >= 0 && index < finalContext.size())
                .distinct()
                .map(finalContext::get)
                .toList();
        return laneContributions(cited);
    }

    public Map<String, Object> learningProjection() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("retrievalDegraded", retrievalDegraded);
        result.put("retrievalCriticalFailure", retrievalCriticalFailure);
        result.put("laneOutcomes", laneOutcomes);
        result.put("selectedLaneContributions", selectedLaneContributions);
        result.put("citedLaneContributions", citedLaneContributions);
        result.put("retrievedCount", retrievedCount);
        result.put("selectedCount", selectedCount);
        result.put("citedCount", citedCount);
        result.put("totalLatencyMs", totalLatencyMs);
        return Map.copyOf(result);
    }
}
