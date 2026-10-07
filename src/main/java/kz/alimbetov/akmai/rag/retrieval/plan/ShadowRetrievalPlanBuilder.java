package kz.alimbetov.akmai.rag.retrieval.plan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.springframework.stereotype.Component;

@Component
public class ShadowRetrievalPlanBuilder {

    private static final Set<RetrievalType> ROUTER_CONTROLLED_LANES = Set.copyOf(
            EnumSet.of(
                    RetrievalType.IDENTIFIER,
                    RetrievalType.VECTOR,
                    RetrievalType.LEXICAL,
                    RetrievalType.CONCEPT,
                    RetrievalType.REFERENCE
            )
    );

    public RetrievalPlan build(
            List<QueryChunk> chunks,
            AdaptiveRetrievalPlanner.ShadowPlanReport report
    ) {
        return build(chunks, report, null);
    }

    /**
     * Builds the router-controlled shadow plan from recommendations while
     * preserving production steps that are outside the router policy surface
     * (for example HYDE_VECTOR). Their dependencies are re-fenced against the
     * resulting step set so shadow replay remains a valid DAG.
     */
    public RetrievalPlan build(
            List<QueryChunk> chunks,
            AdaptiveRetrievalPlanner.ShadowPlanReport report,
            RetrievalPlan productionPlan
    ) {
        if (chunks == null
                || chunks.isEmpty()
                || report == null
                || !report.enabled()
                || report.recommendations().isEmpty()) {
            return productionPlan == null
                    ? new RetrievalPlan(List.of())
                    : productionPlan;
        }

        Map<String, Set<RetrievalType>> recommended = new LinkedHashMap<>();
        report.recommendations().forEach(value ->
                recommended.put(value.queryChunkId(), value.recommendedLanes())
        );

        List<RetrievalStep> steps = new ArrayList<>();
        Set<String> plannedUnits = new LinkedHashSet<>();
        for (QueryChunk chunk : chunks) {
            if (chunk == null || !plannedUnits.add(unitKey(chunk))) {
                continue;
            }
            Set<RetrievalType> lanes = recommended.get(chunk.id());
            if (lanes == null || lanes.isEmpty()) {
                continue;
            }
            append(chunk, lanes, steps);
        }

        if (productionPlan != null && productionPlan.steps() != null) {
            for (RetrievalStep step : productionPlan.steps()) {
                if (step == null
                        || step.type() == null
                        || ROUTER_CONTROLLED_LANES.contains(step.type())) {
                    continue;
                }
                boolean alreadyPresent = steps.stream()
                        .anyMatch(existing -> existing.id().equals(step.id()));
                if (!alreadyPresent) {
                    steps.add(step);
                }
            }
        }

        Set<String> ids = steps.stream()
                .map(RetrievalStep::id)
                .collect(Collectors.toUnmodifiableSet());
        List<RetrievalStep> normalized = steps.stream()
                .map(step -> new RetrievalStep(
                        step.id(),
                        step.queryChunk(),
                        step.type(),
                        step.dependsOn() == null
                                ? List.of()
                                : step.dependsOn().stream()
                                        .filter(ids::contains)
                                        .toList()
                ))
                .toList();
        return new RetrievalPlan(List.copyOf(normalized));
    }

    private void append(
            QueryChunk chunk,
            Set<RetrievalType> lanes,
            List<RetrievalStep> steps
    ) {
        boolean hasIdentifiers = chunk.identifiers() != null
                && !chunk.identifiers().isEmpty();
        boolean hasSemanticText = chunk.semanticText() != null
                && !chunk.semanticText().isBlank();

        RetrievalStep identifier = null;
        if (hasIdentifiers && lanes.contains(RetrievalType.IDENTIFIER)) {
            identifier = step(chunk, RetrievalType.IDENTIFIER, List.of());
            steps.add(identifier);
        }
        if (!hasSemanticText) {
            return;
        }

        List<String> authorityDependency = identifier == null
                ? List.of()
                : List.of(identifier.id());
        List<RetrievalStep> semantic = new ArrayList<>();
        for (RetrievalType type : List.of(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.CONCEPT
        )) {
            if (!lanes.contains(type)) {
                continue;
            }
            RetrievalStep value = step(chunk, type, authorityDependency);
            steps.add(value);
            semantic.add(value);
        }
        if (lanes.contains(RetrievalType.REFERENCE)) {
            steps.add(step(
                    chunk,
                    RetrievalType.REFERENCE,
                    semantic.stream().map(RetrievalStep::id).toList()
            ));
        }
    }

    private RetrievalStep step(
            QueryChunk chunk,
            RetrievalType type,
            List<String> dependencies
    ) {
        String canonical = unitKey(chunk) + "|" + type.name();
        return new RetrievalStep(
                UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8))
                        .toString(),
                chunk,
                type,
                dependencies == null ? List.of() : List.copyOf(dependencies)
        );
    }

    private String unitKey(QueryChunk chunk) {
        String identifiers = chunk.identifiers() == null
                ? ""
                : chunk.identifiers().stream()
                        .map(identifier -> identifier.type().name()
                                + ":"
                                + identifier.normalizedValue())
                        .sorted()
                        .collect(Collectors.joining(","));
        return chunk.normalizedText()
                + "|"
                + chunk.semanticText()
                + "|"
                + chunk.language()
                + "|"
                + identifiers;
    }
}
