package kz.alimbetov.akmai.rag.retrieval.plan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private Recommendation recommend(QueryChunk chunk) {
        EnumSet<RetrievalType> lanes = EnumSet.noneOf(RetrievalType.class);
        boolean hasIdentifiers = chunk.identifiers() != null
                && !chunk.identifiers().isEmpty();
        boolean hasSemanticText = chunk.semanticText() != null
                && !chunk.semanticText().isBlank();

        if (hasIdentifiers) {
            lanes.add(RetrievalType.IDENTIFIER);
            if (!hasSemanticText) {
                return new Recommendation(
                        QueryClass.IDENTIFIER_ONLY,
                        Set.copyOf(lanes)
                );
            }
        }

        SemanticQueryAnalysis semantic = hasSemanticText
                ? safeAnalyze(chunk.semanticText())
                : null;
        boolean strongConcept = semantic != null
                && semantic.hasConcepts()
                && semantic.confidence()
                        >= properties.conceptConfidenceThreshold();
        boolean exactConcept = strongConcept
                && semantic.confidence()
                        >= properties.exactConceptConfidenceThreshold();

        if (hasSemanticText) {
            lanes.add(RetrievalType.VECTOR);
            if (strongConcept) {
                lanes.add(RetrievalType.CONCEPT);
            }
            if (!exactConcept) {
                lanes.add(RetrievalType.LEXICAL);
            }
            lanes.add(RetrievalType.REFERENCE);
        }

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
        GENERIC
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
