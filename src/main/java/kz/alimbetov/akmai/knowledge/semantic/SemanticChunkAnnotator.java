package kz.alimbetov.akmai.knowledge.semantic;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SemanticChunkAnnotator {

    private static final int MAX_DOMAINS = 3;
    private static final int MAX_CONCEPTS = 12;

    private final SemanticDomainRouter router;
    private final SemanticConceptMatcher conceptMatcher;

    public SemanticChunkAnnotator(SemanticDomainRouter router) {
        this(router, null);
    }

    @Autowired
    public SemanticChunkAnnotator(
            SemanticDomainRouter router,
            SemanticConceptMatcher conceptMatcher
    ) {
        this.router = router;
        this.conceptMatcher = conceptMatcher;
    }

    public KnowledgeChunk annotate(KnowledgeChunk chunk) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }

        SemanticQueryProfile profile = router.analyze(
                chunk.rawText(),
                chunk.language()
        );
        List<SemanticConceptMatch> conceptMatches =
                conceptMatcher == null
                        ? List.of()
                        : conceptMatcher.match(
                                chunk.rawText(),
                                chunk.language()
                        );

        if (profile.domains().isEmpty() && conceptMatches.isEmpty()) {
            return chunk;
        }

        LinkedHashMap<String, Double> domainScores =
                new LinkedHashMap<>();
        profile.domains().forEach(domain ->
                domainScores.merge(
                        domain.domainId(),
                        domain.score(),
                        Double::sum
                )
        );
        conceptMatches.forEach(match ->
                domainScores.merge(
                        match.domainId(),
                        match.weight(),
                        Double::sum
                )
        );

        List<String> selectedDomains = domainScores.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Double>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry::getKey)
                )
                .limit(MAX_DOMAINS)
                .map(Map.Entry::getKey)
                .toList();

        Map<String, Object> metadata = new HashMap<>(
                chunk.metadata() == null ? Map.of() : chunk.metadata()
        );
        metadata.put("semanticOntologyVersion", profile.ontologyVersion());
        metadata.put("semanticDomains", selectedDomains);

        LinkedHashMap<String, Double> selectedScores =
                new LinkedHashMap<>();
        selectedDomains.forEach(domainId ->
                selectedScores.put(domainId, domainScores.get(domainId))
        );
        metadata.put(
                "semanticDomainScores",
                Map.copyOf(selectedScores)
        );

        if (!conceptMatches.isEmpty()) {
            List<SemanticConceptMatch> selectedConcepts =
                    new ArrayList<>(conceptMatches);
            selectedConcepts.sort(
                    Comparator.comparingDouble(
                                    SemanticConceptMatch::weight
                            )
                            .reversed()
                            .thenComparing(
                                    SemanticConceptMatch::conceptId
                            )
            );
            selectedConcepts = selectedConcepts.stream()
                    .limit(MAX_CONCEPTS)
                    .toList();

            metadata.put(
                    "semanticConceptVersion",
                    conceptMatcher.version(chunk.language())
            );
            metadata.put(
                    "semanticConcepts",
                    selectedConcepts.stream()
                            .map(SemanticConceptMatch::conceptId)
                            .toList()
            );
            metadata.put(
                    "semanticConceptPhrases",
                    selectedConcepts.stream()
                            .map(SemanticConceptMatch::phrase)
                            .toList()
            );
        }

        return new KnowledgeChunk(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.parentChunkId(),
                chunk.chunkIndex(),
                chunk.rawText(),
                chunk.normalizedText(),
                chunk.embeddingText(),
                chunk.title(),
                chunk.sectionPath(),
                chunk.language(),
                chunk.domain(),
                chunk.references(),
                Map.copyOf(metadata)
        );
    }
}
