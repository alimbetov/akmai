package kz.alimbetov.akmai.rag.retrieval.plan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.semantic.SemanticMatchMode;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveRetrievalPlanner {

    private final SemanticQueryAnalyzer semanticQueryAnalyzer;
    private final AdaptiveRetrievalProperties properties;

    public AdaptiveRetrievalPlanner(
            SemanticQueryAnalyzer semanticQueryAnalyzer,
            AdaptiveRetrievalProperties properties
    ) {
        this.semanticQueryAnalyzer = semanticQueryAnalyzer;
        this.properties = properties;
    }

    /**
     * Applies only conservative lane reductions to the existing baseline plan.
     * The adaptive planner never creates a retrieval lane that the baseline
     * planner did not already schedule. If semantic analysis is unavailable or
     * the query chunk is not safely classifiable, the baseline is preserved.
     */
    public RetrievalPlan enforce(
            List<QueryChunk> chunks,
            RetrievalPlan currentPlan
    ) {
        if (!properties.enabled()
                || currentPlan == null
                || currentPlan.steps() == null
                || currentPlan.steps().isEmpty()
                || chunks == null
                || chunks.isEmpty()) {
            return currentPlan;
        }

        Map<String, Set<RetrievalType>> recommended = new LinkedHashMap<>();
        for (QueryChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            recommended.put(chunk.id(), recommend(chunk).lanes());
        }
        if (recommended.isEmpty()) {
            return currentPlan;
        }

        List<RetrievalStep> kept = currentPlan.steps().stream()
                .filter(step -> keep(step, recommended))
                .toList();
        if (kept.size() == currentPlan.steps().size()) {
            return currentPlan;
        }

        Set<String> keptIds = new HashSet<>();
        kept.forEach(step -> keptIds.add(step.id()));
        List<RetrievalStep> normalized = kept.stream()
                .map(step -> new RetrievalStep(
                        step.id(),
                        step.queryChunk(),
                        step.type(),
                        step.dependsOn() == null
                                ? List.of()
                                : step.dependsOn().stream()
                                        .filter(keptIds::contains)
                                        .toList()
                ))
                .toList();
        return new RetrievalPlan(List.copyOf(normalized));
    }

    public ShadowPlanReport shadow(
            List<QueryChunk> chunks,
            RetrievalPlan currentPlan
    ) {
        if (!properties.shadowEnabled()) {
            return new ShadowPlanReport(false, List.of());
        }
        if (chunks == null || chunks.isEmpty()) {
            return new ShadowPlanReport(true, List.of());
        }

        Map<String, Set<RetrievalType>> current = currentLanes(currentPlan);
        List<ChunkRecommendation> recommendations = new ArrayList<>();
        for (QueryChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            Recommendation recommendation = recommend(chunk);
            recommendations.add(new ChunkRecommendation(
                    chunk.id(),
                    recommendation.queryClass(),
                    current.getOrDefault(chunk.id(), Set.of()),
                    recommendation.lanes()
            ));
        }
        return new ShadowPlanReport(true, List.copyOf(recommendations));
    }

    private boolean keep(
            RetrievalStep step,
            Map<String, Set<RetrievalType>> recommended
    ) {
        if (step == null || step.queryChunk() == null || step.type() == null) {
            return false;
        }
        Set<RetrievalType> lanes = recommended.get(step.queryChunk().id());
        return lanes == null || lanes.contains(step.type());
    }

    private Recommendation recommend(QueryChunk chunk) {
        EnumSet<RetrievalType> baseline = baselineLanes(chunk);
        boolean hasIdentifiers = chunk.identifiers() != null
                && !chunk.identifiers().isEmpty();
        boolean hasSemanticText = chunk.semanticText() != null
                && !chunk.semanticText().isBlank();

        if (hasIdentifiers && !hasSemanticText) {
            return new Recommendation(
                    QueryClass.IDENTIFIER_ONLY,
                    Set.copyOf(baseline)
            );
        }
        if (!hasSemanticText) {
            return new Recommendation(
                    QueryClass.ANALYSIS_UNAVAILABLE,
                    Set.copyOf(baseline)
            );
        }

        SemanticQueryAnalysis semantic = safeAnalyze(chunk.semanticText());
        if (semantic == null) {
            return new Recommendation(
                    QueryClass.ANALYSIS_UNAVAILABLE,
                    Set.copyOf(baseline)
            );
        }

        EnumSet<RetrievalType> lanes = EnumSet.noneOf(RetrievalType.class);
        if (hasIdentifiers) {
            lanes.add(RetrievalType.IDENTIFIER);
        }

        boolean strongConcept = semantic.hasConcepts()
                && semantic.confidence()
                        >= properties.conceptConfidenceThreshold();
        boolean exactConcept = strongConcept
                && semantic.confidence()
                        >= properties.exactConceptConfidenceThreshold()
                && semantic.concepts().stream()
                        .allMatch(match ->
                                match.matchMode() == SemanticMatchMode.EXACT
                        );

        lanes.add(RetrievalType.VECTOR);
        if (strongConcept) {
            lanes.add(RetrievalType.CONCEPT);
        }
        if (!exactConcept) {
            lanes.add(RetrievalType.LEXICAL);
        }
        lanes.add(RetrievalType.REFERENCE);

        QueryClass queryClass;
        if (hasIdentifiers) {
            queryClass = strongConcept
                    ? QueryClass.IDENTIFIER_CONCEPTUAL
                    : QueryClass.IDENTIFIER_SEMANTIC;
        } else if (exactConcept) {
            queryClass = QueryClass.CONCEPTUAL_EXACT;
        } else if (strongConcept) {
            queryClass = QueryClass.CONCEPTUAL_FUZZY;
        } else {
            queryClass = QueryClass.GENERIC;
        }
        return new Recommendation(queryClass, Set.copyOf(lanes));
    }

    private EnumSet<RetrievalType> baselineLanes(QueryChunk chunk) {
        EnumSet<RetrievalType> lanes = EnumSet.noneOf(RetrievalType.class);
        boolean hasIdentifiers = chunk.identifiers() != null
                && !chunk.identifiers().isEmpty();
        boolean hasSemanticText = chunk.semanticText() != null
                && !chunk.semanticText().isBlank();

        if (hasIdentifiers) {
            lanes.add(RetrievalType.IDENTIFIER);
            if (!hasSemanticText) {
                return lanes;
            }
        }

        lanes.add(RetrievalType.VECTOR);
        lanes.add(RetrievalType.LEXICAL);
        lanes.add(RetrievalType.CONCEPT);
        lanes.add(RetrievalType.REFERENCE);
        return lanes;
    }

    private SemanticQueryAnalysis safeAnalyze(String text) {
        try {
            return semanticQueryAnalyzer.analyze(text);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, Set<RetrievalType>> currentLanes(
            RetrievalPlan currentPlan
    ) {
        LinkedHashMap<String, EnumSet<RetrievalType>> collected =
                new LinkedHashMap<>();
        if (currentPlan != null && currentPlan.steps() != null) {
            for (RetrievalStep step : currentPlan.steps()) {
                if (step == null || step.queryChunk() == null || step.type() == null) {
                    continue;
                }
                collected.computeIfAbsent(
                                step.queryChunk().id(),
                                ignored -> EnumSet.noneOf(RetrievalType.class)
                        )
                        .add(step.type());
            }
        }
        LinkedHashMap<String, Set<RetrievalType>> result = new LinkedHashMap<>();
        collected.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    public enum QueryClass {
        IDENTIFIER_ONLY,
        IDENTIFIER_SEMANTIC,
        IDENTIFIER_CONCEPTUAL,
        CONCEPTUAL_EXACT,
        CONCEPTUAL_FUZZY,
        GENERIC,
        ANALYSIS_UNAVAILABLE
    }

    public record ChunkRecommendation(
            String queryChunkId,
            QueryClass queryClass,
            Set<RetrievalType> currentLanes,
            Set<RetrievalType> recommendedLanes
    ) {
        public ChunkRecommendation {
            currentLanes = currentLanes == null
                    ? Set.of()
                    : Set.copyOf(currentLanes);
            recommendedLanes = recommendedLanes == null
                    ? Set.of()
                    : Set.copyOf(recommendedLanes);
        }
    }

    public record ShadowPlanReport(
            boolean enabled,
            List<ChunkRecommendation> recommendations
    ) {
        public ShadowPlanReport {
            recommendations = recommendations == null
                    ? List.of()
                    : List.copyOf(recommendations);
        }
    }

    private record Recommendation(
            QueryClass queryClass,
            Set<RetrievalType> lanes
    ) {
    }
}
