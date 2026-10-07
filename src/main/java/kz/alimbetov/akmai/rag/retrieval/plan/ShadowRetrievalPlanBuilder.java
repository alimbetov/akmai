package kz.alimbetov.akmai.rag.retrieval.plan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    public RetrievalPlan build(
            List<QueryChunk> chunks,
            AdaptiveRetrievalPlanner.ShadowPlanReport report
    ) {
        if (chunks == null
                || chunks.isEmpty()
                || report == null
                || !report.enabled()
                || report.recommendations().isEmpty()) {
            return new RetrievalPlan(List.of());
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
        return new RetrievalPlan(List.copyOf(steps));
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
