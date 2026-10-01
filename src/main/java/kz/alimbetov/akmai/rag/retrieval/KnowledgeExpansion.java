package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeExpansion {

    private static final int MAX_SEEDS = 5;
    private static final int NEIGHBOR_RADIUS = 1;
    private static final int MAX_EXPANDED = 10;

    private final SearchProjectionRepository repository;

    public KnowledgeExpansion(SearchProjectionRepository repository) {
        this.repository = repository;
    }

    public List<RetrievalHit> expand(List<RetrievalHit> ranked) {
        Set<String> existing = new HashSet<>();
        ranked.stream()
                .map(RetrievalHit::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(existing::add);

        List<RetrievalHit> expanded = new ArrayList<>();
        ranked.stream().limit(MAX_SEEDS).forEach(seed -> {
            Integer chunkIndex = integerMetadata(seed, "chunkIndex");
            if (chunkIndex == null || seed.documentId() == null) {
                return;
            }
            repository.findAdjacent(
                    seed.documentId(),
                    chunkIndex,
                    NEIGHBOR_RADIUS
            ).stream()
                    .filter(projection -> existing.add(projection.chunkId()))
                    .limit(MAX_EXPANDED - expanded.size())
                    .map(this::neighbor)
                    .forEach(expanded::add);
        });

        List<RetrievalHit> result = new ArrayList<>(ranked);
        result.addAll(expanded);
        return List.copyOf(result);
    }

    private RetrievalHit neighbor(SearchProjection projection) {
        return new RetrievalHit(
                RetrievalType.REFERENCE,
                projection.documentId(),
                projection.chunkId(),
                projection.text(),
                Map.of(
                        "language", projection.language(),
                        "sectionPath", projection.sectionPath() == null
                                ? ""
                                : projection.sectionPath(),
                        "chunkIndex", projection.chunkIndex(),
                        "expansion", "neighbor"
                )
        );
    }

    private Integer integerMetadata(RetrievalHit hit, String key) {
        Object value = hit.metadata().get(key);
        return value instanceof Number number ? number.intValue() : null;
    }
}
