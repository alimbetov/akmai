package kz.alimbetov.akmai.rag.retrieval.plan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.semantic.SemanticMatchMode;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.policy.ApprovedRetrievalPolicyProvider;
import kz.alimbetov.akmai.rag.policy.ShadowRetrievalPolicyProvider;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveRetrievalPlanner {

    private final SemanticQueryAnalyzer semanticQueryAnalyzer;
    private final AdaptiveRetrievalProperties properties;
    private final ApprovedRetrievalPolicyProvider approvedPolicyProvider;
    private final ShadowRetrievalPolicyProvider shadowPolicyProvider;

    public AdaptiveRetrievalPlanner(
            SemanticQueryAnalyzer semanticQueryAnalyzer,
            AdaptiveRetrievalProperties properties
    ) {
        this(semanticQueryAnalyzer, properties, null, null);
    }

    public AdaptiveRetrievalPlanner(
            SemanticQueryAnalyzer semanticQueryAnalyzer,
            AdaptiveRetrievalProperties properties,
            ApprovedRetrievalPolicyProvider approvedPolicyProvider
    ) {
        this(
                semanticQueryAnalyzer,
                properties,
                approvedPolicyProvider,
                null
        );
    }

    @Autowired
    public AdaptiveRetrievalPlanner(
            SemanticQueryAnalyzer semanticQueryAnalyzer,
            AdaptiveRetrievalProperties properties,
            ApprovedRetrievalPolicyProvider approvedPolicyProvider,
            ShadowRetrievalPolicyProvider shadowPolicyProvider
    ) {
        this.semanticQueryAnalyzer = semanticQueryAnalyzer;
        this.properties = properties;
        this.approvedPolicyProvider = approvedPolicyProvider;
        this.shadowPolicyProvider = shadowPolicyProvider;
    }

    /**
     * Production execution is evidence-gated. With the Spring policy provider
     * present, an enabled planner changes the baseline only when an APPROVED
     * retrieval policy contains a route for the classified query. Older
     * constructors retain heuristic behavior for isolated tests.
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
            Recommendation heuristic = recommend(chunk);
            Optional<Set<RetrievalType>> approved = approvedPolicyProvider == null
                    ? Optional.of(heuristic.lanes())
                    : approvedPolicyProvider.lanes(heuristic.queryClass());
            if (approved.isEmpty()) {
                return currentPlan;
            }
            recommended.put(
                    chunk.id(),
                    safePolicyLanes(chunk, approved.get())
            );
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

    public QueryClass classifyPrimary(List<QueryChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return QueryClass.ANALYSIS_UNAVAILABLE;
        }
        List<QueryClass> classes = chunks.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::recommend)
                .map(Recommendation::queryClass)
                .toList();
        if (classes.isEmpty()) {
            return QueryClass.ANALYSIS_UNAVAILABLE;
        }
        if (classes.stream().distinct().count() == 1) {
            return classes.getFirst();
        }
        if (classes.contains(QueryClass.IDENTIFIER_CONCEPTUAL)) {
            return QueryClass.IDENTIFIER_CONCEPTUAL;
        }
        if (classes.contains(QueryClass.IDENTIFIER_SEMANTIC)) {
            return QueryClass.IDENTIFIER_SEMANTIC;
        }
        if (classes.contains(QueryClass.IDENTIFIER_ONLY)) {
            return QueryClass.IDENTIFIER_ONLY;
        }
        return QueryClass.GENERIC;
    }

    /**
     * Shadow recommendations never alter the execution plan. If a SHADOW
     * policy exists it is evaluated here; otherwise the deterministic heuristic
     * remains available as observational telemetry.
     */
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
            Recommendation heuristic = recommend(chunk);
            Set<RetrievalType> lanes = shadowPolicyProvider == null
                    ? heuristic.lanes()
                    : shadowPolicyProvider.lanes(heuristic.queryClass())
                            .map(value -> safePolicyLanes(chunk, value))
                            .orElse(heuristic.lanes());
            recommendations.add(new ChunkRecommendation(
                    chunk.id(),
                    heuristic.queryClass(),
                    current.getOrDefault(chunk.id(), Set.of()),
                    lanes
            ));
        }
        return new ShadowPlanReport(true, List.copyOf(recommendations));
    }

    private Set<RetrievalType> safePolicyLanes(
            QueryChunk chunk,
            Set<RetrievalType> requested
    ) {
        EnumSet<RetrievalType> baseline = baselineLanes(chunk);
        EnumSet<RetrievalType> result = EnumSet.noneOf(RetrievalType.class);
        if (requested != null) {
            result.addAll(requested);
            result.retainAll(baseline);
        }

        boolean hasIdentifiers = chunk.identifiers() != null
                && !chunk.identifiers().isEmpty();
        boolean hasSemanticText = chunk.semanticText() != null
                && !chunk.semanticText().isBlank();
        if (hasIdentifiers) {
            result.add(RetrievalType.IDENTIFIER);
        }
        if (hasSemanticText) {
            result.add(RetrievalType.VECTOR);
            if (baseline.contains(RetrievalType.REFERENCE)) {
                result.add(RetrievalType.REFERENCE);
            }
        }
        if (result.isEmpty()) {
            result.addAll(baseline);
        }
        return Set.copyOf(result);
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

        if (!hasSemanticText) {
            lanes.add(RetrievalType.VECTOR);
            lanes.add(RetrievalType.LEXICAL);
            lanes.add(RetrievalType.REFERENCE);
            lanes.add(RetrievalType.CONCEPT);
            return lanes;
        }

        lanes.add(RetrievalType.VECTOR);
        lanes.add(RetrievalType.LEXICAL);
        lanes.add(RetrievalType.REFERENCE);
        lanes.add(RetrievalType.CONCEPT);
        return lanes;
    }

    private SemanticQueryAnalysis safeAnalyze(String semanticText) {
        try {
            return semanticQueryAnalyzer.analyze(semanticText);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, Set<RetrievalType>> currentLanes(RetrievalPlan currentPlan) {
        Map<String, Set<RetrievalType>> result = new LinkedHashMap<>();
        if (currentPlan == null || currentPlan.steps() == null) {
            return Map.of();
        }
        for (RetrievalStep step : currentPlan.steps()) {
            if (step == null || step.queryChunk() == null || step.type() == null) {
                continue;
            }
            result.computeIfAbsent(
                    step.queryChunk().id(),
                    ignored -> EnumSet.noneOf(RetrievalType.class)
            ).add(step.type());
        }
        return result.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())
                ));
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
