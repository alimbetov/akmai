package kz.alimbetov.akmai.knowledge.semantic;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import org.springframework.stereotype.Component;

@Component
public class SemanticChunkAnnotator {

    private static final int MAX_DOMAINS = 3;

    private final SemanticDomainRouter router;

    public SemanticChunkAnnotator(SemanticDomainRouter router) {
        this.router = router;
    }

    public KnowledgeChunk annotate(KnowledgeChunk chunk) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }

        SemanticQueryProfile profile = router.analyze(
                chunk.rawText(),
                chunk.language()
        );
        if (profile.domains().isEmpty()) {
            return chunk;
        }

        List<SemanticDomainScore> selected = profile.domains().stream()
                .limit(MAX_DOMAINS)
                .toList();

        Map<String, Object> metadata = new HashMap<>(
                chunk.metadata() == null ? Map.of() : chunk.metadata()
        );
        metadata.put("semanticOntologyVersion", profile.ontologyVersion());
        metadata.put(
                "semanticDomains",
                selected.stream()
                        .map(SemanticDomainScore::domainId)
                        .toList()
        );

        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        selected.forEach(domain ->
                scores.put(domain.domainId(), domain.score())
        );
        metadata.put("semanticDomainScores", Map.copyOf(scores));

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
