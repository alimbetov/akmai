package kz.alimbetov.akmai.rag.retrieval;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptMatch;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class ConceptRetrievalStrategy implements RetrievalStrategy {

    private static final int MAX_QUERY_CONCEPTS = 4;
    private static final int MAX_RESULT_CANDIDATES = 4;

    private final PublishedSearchProjectionReader repository;
    private final SemanticQueryAnalyzer semanticQueryAnalyzer;
    private final RetrievalProperties properties;

    public ConceptRetrievalStrategy(
            PublishedSearchProjectionReader repository,
            SemanticQueryAnalyzer semanticQueryAnalyzer,
            RetrievalProperties properties
    ) {
        this.repository = repository;
        this.semanticQueryAnalyzer = semanticQueryAnalyzer;
        this.properties = properties;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.CONCEPT;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        if (queryChunk == null
                || queryChunk.semanticText() == null
                || queryChunk.semanticText().isBlank()) {
            return List.of();
        }

        SemanticQueryAnalysis analysis;
        try {
            analysis = semanticQueryAnalyzer.analyze(
                    queryChunk.semanticText()
            );
        } catch (RuntimeException exception) {
            return List.of();
        }

        List<String> conceptIds = analysis.concepts().stream()
                .sorted(
                        Comparator.comparingDouble(
                                        SemanticConceptMatch::weight
                                )
                                .reversed()
                                .thenComparing(
                                        SemanticConceptMatch::conceptId
                                )
                )
                .map(SemanticConceptMatch::conceptId)
                .distinct()
                .limit(MAX_QUERY_CONCEPTS)
                .toList();
        if (conceptIds.isEmpty()) {
            return List.of();
        }

        int limit = Math.min(
                MAX_RESULT_CANDIDATES,
                properties.lexicalLimit()
        );
        List<SearchProjection> projections =
                repository.searchSemanticConcepts(
                        conceptIds,
                        List.copyOf(context.documentIds()),
                        context.accessLevels(),
                        limit
                );

        return projections.stream()
                .map(projection -> toHit(projection, conceptIds))
                .toList();
    }

    private RetrievalHit toHit(
            SearchProjection projection,
            List<String> queryConceptIds
    ) {
        Map<String, Object> metadata =
                new HashMap<>(projection.metadata());
        metadata.put("language", projection.language());
        metadata.put(
                "sectionPath",
                projection.sectionPath() == null
                        ? ""
                        : projection.sectionPath()
        );
        metadata.put("chunkIndex", projection.chunkIndex());
        metadata.put("generation", projection.generation());
        metadata.put("semanticConceptRetrieval", true);
        metadata.put("semanticQueryConcepts", queryConceptIds);

        int overlap = overlapCount(
                projection.metadata(),
                Set.copyOf(queryConceptIds)
        );
        metadata.put("semanticConceptOverlap", overlap);
        metadata.put(
                "score",
                queryConceptIds.isEmpty()
                        ? 0.0
                        : (double) overlap / queryConceptIds.size()
        );

        return new RetrievalHit(
                RetrievalType.CONCEPT,
                projection.accessLevel(),
                projection.documentId(),
                projection.generation(),
                projection.chunkId(),
                projection.text(),
                metadata
        );
    }

    private int overlapCount(
            Map<String, Object> metadata,
            Set<String> queryConceptIds
    ) {
        Object raw = metadata.get("semanticConcepts");
        if (!(raw instanceof Iterable<?> values)) {
            return 0;
        }

        LinkedHashSet<String> matched = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String conceptId
                    && queryConceptIds.contains(conceptId)) {
                matched.add(conceptId);
            }
        }
        return matched.size();
    }
}
