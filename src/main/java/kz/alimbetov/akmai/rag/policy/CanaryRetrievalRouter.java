package kz.alimbetov.akmai.rag.policy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import kz.alimbetov.akmai.rag.retrieval.plan.ShadowRetrievalPlanBuilder;
import org.springframework.stereotype.Component;

@Component
public class CanaryRetrievalRouter {

    private final CanaryRetrievalPolicyProvider policyProvider;
    private final CanaryEvaluationProperties properties;
    private final AdaptiveRetrievalPlanner adaptiveRetrievalPlanner;
    private final ShadowRetrievalPlanBuilder planBuilder;
    private final CanaryRoutingObservationStore observationStore;

    public CanaryRetrievalRouter(
            CanaryRetrievalPolicyProvider policyProvider,
            CanaryEvaluationProperties properties,
            AdaptiveRetrievalPlanner adaptiveRetrievalPlanner,
            ShadowRetrievalPlanBuilder planBuilder,
            CanaryRoutingObservationStore observationStore
    ) {
        this.policyProvider = policyProvider;
        this.properties = properties;
        this.adaptiveRetrievalPlanner = adaptiveRetrievalPlanner;
        this.planBuilder = planBuilder;
        this.observationStore = observationStore;
    }

    public RetrievalPlan route(
            String requestId,
            List<QueryChunk> chunks,
            RetrievalPlan baselinePlan,
            RetrievalPlan productionPlan
    ) {
        observationStore.clearCurrent();
        if (chunks == null
                || chunks.isEmpty()
                || baselinePlan == null
                || productionPlan == null) {
            return productionPlan;
        }
        String policyVersion = policyProvider.canaryVersion().orElse(null);
        if (policyVersion == null || policyVersion.isBlank()) {
            return productionPlan;
        }

        Map<String, Set<RetrievalType>> productionLanes = lanes(productionPlan);
        List<AdaptiveRetrievalPlanner.ChunkRecommendation> recommendations =
                new ArrayList<>();
        for (QueryChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            AdaptiveRetrievalPlanner.QueryClass queryClass =
                    adaptiveRetrievalPlanner.classifyPrimary(List.of(chunk));
            Set<RetrievalType> current = productionLanes.getOrDefault(
                    chunk.id(),
                    Set.of()
            );
            Set<RetrievalType> candidate = policyProvider.lanes(queryClass)
                    .map(value -> safePolicyLanes(chunk, value))
                    .orElse(current);
            recommendations.add(new AdaptiveRetrievalPlanner.ChunkRecommendation(
                    chunk.id(),
                    queryClass,
                    current,
                    candidate
            ));
        }
        if (recommendations.isEmpty()) {
            return productionPlan;
        }

        RetrievalPlan candidatePlan = planBuilder.build(
                chunks,
                new AdaptiveRetrievalPlanner.ShadowPlanReport(
                        true,
                        List.copyOf(recommendations)
                ),
                productionPlan
        );
        if (samePlan(candidatePlan, productionPlan)) {
            return productionPlan;
        }

        String routingKey = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId;
        CanaryRoutingObservationStore.Cohort cohort = sampled(routingKey)
                ? CanaryRoutingObservationStore.Cohort.CANARY
                : CanaryRoutingObservationStore.Cohort.CONTROL;
        String queryClass = adaptiveRetrievalPlanner.classifyPrimary(chunks).name();
        observationStore.recordCurrent(
                new CanaryRoutingObservationStore.Decision(
                        policyVersion,
                        cohort,
                        queryClass,
                        true,
                        Instant.now()
                )
        );
        return cohort == CanaryRoutingObservationStore.Cohort.CANARY
                ? candidatePlan
                : productionPlan;
    }

    private Set<RetrievalType> safePolicyLanes(
            QueryChunk chunk,
            Set<RetrievalType> requested
    ) {
        EnumSet<RetrievalType> baseline = EnumSet.noneOf(RetrievalType.class);
        boolean hasIdentifiers = chunk.identifiers() != null
                && !chunk.identifiers().isEmpty();
        boolean hasSemanticText = chunk.semanticText() != null
                && !chunk.semanticText().isBlank();
        if (hasIdentifiers) {
            baseline.add(RetrievalType.IDENTIFIER);
        }
        if (hasSemanticText) {
            baseline.add(RetrievalType.VECTOR);
            baseline.add(RetrievalType.LEXICAL);
            baseline.add(RetrievalType.CONCEPT);
            baseline.add(RetrievalType.REFERENCE);
        }

        EnumSet<RetrievalType> result = EnumSet.noneOf(RetrievalType.class);
        if (requested != null) {
            result.addAll(requested);
            result.retainAll(baseline);
        }
        if (hasIdentifiers) {
            result.add(RetrievalType.IDENTIFIER);
        }
        if (hasSemanticText) {
            result.add(RetrievalType.VECTOR);
            result.add(RetrievalType.REFERENCE);
        }
        if (result.isEmpty()) {
            result.addAll(baseline);
        }
        return Set.copyOf(result);
    }

    private Map<String, Set<RetrievalType>> lanes(RetrievalPlan plan) {
        Map<String, EnumSet<RetrievalType>> mutable = new LinkedHashMap<>();
        if (plan.steps() != null) {
            for (RetrievalStep step : plan.steps()) {
                if (step == null
                        || step.queryChunk() == null
                        || step.type() == null
                        || step.type() == RetrievalType.HYDE_VECTOR) {
                    continue;
                }
                mutable.computeIfAbsent(
                        step.queryChunk().id(),
                        ignored -> EnumSet.noneOf(RetrievalType.class)
                ).add(step.type());
            }
        }
        Map<String, Set<RetrievalType>> result = new LinkedHashMap<>();
        mutable.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    private boolean samePlan(RetrievalPlan left, RetrievalPlan right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.steps().stream().map(RetrievalStep::id).toList()
                .equals(right.steps().stream().map(RetrievalStep::id).toList());
    }

    private boolean sampled(String requestId) {
        UUID value;
        try {
            value = UUID.fromString(requestId);
        } catch (IllegalArgumentException exception) {
            value = UUID.nameUUIDFromBytes(
                    requestId.getBytes(StandardCharsets.UTF_8)
            );
        }
        long mixed = value.getMostSignificantBits()
                ^ Long.rotateLeft(value.getLeastSignificantBits(), 23);
        int bucket = Math.floorMod(
                (int) (mixed ^ (mixed >>> 32)),
                10_000
        );
        int threshold = (int) Math.round(properties.trafficPercent() * 100.0);
        return bucket < threshold;
    }
}
