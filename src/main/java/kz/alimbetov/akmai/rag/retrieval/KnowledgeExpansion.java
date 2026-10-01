package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeExpansion {

    private final SearchProjectionRepository repository;
    private final RetrievalProperties properties;

    public KnowledgeExpansion(SearchProjectionRepository repository, RetrievalProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public List<RetrievalHit> expand(List<RetrievalHit> ranked) {
        Set<String> existing = new HashSet<>();
        List<String> seedIds = ranked.stream()
                .limit(properties.expansionSeeds())
                .map(RetrievalHit::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .filter(existing::add)
                .toList();

        ranked.stream()
                .skip(properties.expansionSeeds())
                .map(RetrievalHit::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(existing::add);

        Map<String, SearchProjection> seeds = repository.findByChunkIds(seedIds).stream()
                .collect(Collectors.toMap(
                        SearchProjection::chunkId,
                        Function.identity(),
                        (left, right) -> left
                ));

        List<RetrievalHit> expanded = new ArrayList<>();
        for (String seedId : seedIds) {
            if (expanded.size() >= properties.expansionMax()) {
                break;
            }
            SearchProjection seed = seeds.get(seedId);
            if (seed == null) {
                continue;
            }

            repository.findAdjacent(
                    seed.documentId(),
                    seed.chunkIndex(),
                    properties.expansionRadius()
            ).stream()
                    .filter(projection -> existing.add(projection.chunkId()))
                    .limit(properties.expansionMax() - expanded.size())
                    .map(this::neighbor)
                    .forEach(expanded::add);
        }

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
}
