package kz.alimbetov.akmai.rag.retrieval.plan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.rag.query.AdvancedRetrievalProperties;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryOrigin;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RetrievalPlanner {

    private final AdaptiveRetrievalPlanner adaptiveRetrievalPlanner;
    private final AdvancedRetrievalProperties advancedProperties;

    public RetrievalPlanner() {
        this(null, null);
    }

    public RetrievalPlanner(
            AdaptiveRetrievalPlanner adaptiveRetrievalPlanner
    ) {
        this(adaptiveRetrievalPlanner, null);
    }

    @Autowired
    public RetrievalPlanner(
            AdaptiveRetrievalPlanner adaptiveRetrievalPlanner,
            AdvancedRetrievalProperties advancedProperties
    ) {
        this.adaptiveRetrievalPlanner = adaptiveRetrievalPlanner;
        this.advancedProperties = advancedProperties;
    }

    public RetrievalPlan plan(List<QueryChunk> chunks) {
        List<RetrievalStep> steps = new ArrayList<>();
        Set<String> plannedUnits = new LinkedHashSet<>();

        for (QueryChunk chunk : chunks) {
            if (!plannedUnits.add(unitKey(chunk))) {
                continue;
            }

            if (chunk.identifiers().isEmpty()) {
                RetrievalStep vector = step(chunk, RetrievalType.VECTOR, List.of());
                RetrievalStep lexical = step(chunk, RetrievalType.LEXICAL, List.of());
                RetrievalStep concept = step(chunk, RetrievalType.CONCEPT, List.of());
                steps.add(vector);
                steps.add(lexical);
                steps.add(concept);
                steps.add(step(
                        chunk,
                        RetrievalType.REFERENCE,
                        List.of(vector.id(), lexical.id(), concept.id())
                ));
                continue;
            }

            RetrievalStep identifier = step(chunk, RetrievalType.IDENTIFIER, List.of());
            steps.add(identifier);

            if (!chunk.semanticText().isBlank()) {
                RetrievalStep vector = step(
                        chunk,
                        RetrievalType.VECTOR,
                        List.of(identifier.id())
                );
                RetrievalStep lexical = step(
                        chunk,
                        RetrievalType.LEXICAL,
                        List.of(identifier.id())
                );
                RetrievalStep concept = step(
                        chunk,
                        RetrievalType.CONCEPT,
                        List.of(identifier.id())
                );
                steps.add(vector);
                steps.add(lexical);
                steps.add(concept);
                steps.add(step(
                        chunk,
                        RetrievalType.REFERENCE,
                        List.of(vector.id(), lexical.id(), concept.id())
                ));
            }
        }

        RetrievalPlan baseline = new RetrievalPlan(List.copyOf(steps));
        RetrievalPlan planned = adaptiveRetrievalPlanner == null
                ? baseline
                : adaptiveRetrievalPlanner.enforce(chunks, baseline);
        return appendSelectiveHyde(chunks, planned);
    }

    private RetrievalPlan appendSelectiveHyde(
            List<QueryChunk> chunks,
            RetrievalPlan planned
    ) {
        if (advancedProperties == null
                || !advancedProperties.hydeEnabled()
                || chunks == null
                || chunks.isEmpty()) {
            return planned;
        }

        QueryChunk candidate = chunks.stream()
                .filter(chunk -> chunk != null)
                .filter(chunk -> chunk.origin() == QueryOrigin.ORIGINAL)
                .filter(chunk -> chunk.index() == 0)
                .filter(chunk -> chunk.identifiers() == null
                        || chunk.identifiers().isEmpty())
                .filter(chunk -> chunk.semanticText() != null
                        && !chunk.semanticText().isBlank())
                .findFirst()
                .orElse(null);
        if (candidate == null) {
            return planned;
        }

        boolean vectorLanePresent = planned.steps().stream()
                .anyMatch(existing -> existing.type() == RetrievalType.VECTOR
                        && existing.queryChunk() != null
                        && existing.queryChunk().id().equals(candidate.id()));
        if (!vectorLanePresent) {
            return planned;
        }

        List<RetrievalStep> result = new ArrayList<>(planned.steps());
        result.add(step(candidate, RetrievalType.HYDE_VECTOR, List.of()));
        return new RetrievalPlan(List.copyOf(result));
    }

    private RetrievalStep step(
            QueryChunk chunk,
            RetrievalType type,
            List<String> dependencies
    ) {
        String canonical = unitKey(chunk) + "|" + type.name();
        return new RetrievalStep(
                UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)).toString(),
                chunk,
                type,
                dependencies
        );
    }

    private String unitKey(QueryChunk chunk) {
        String identifiers = chunk.identifiers().stream()
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
