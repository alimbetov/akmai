package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class ReferenceRetrievalStrategy implements RetrievalStrategy {

    private final SearchProjectionRepository repository;

    public ReferenceRetrievalStrategy(SearchProjectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.REFERENCE;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        List<String> referencedChunkIds = context.dependencyHits().stream()
                .flatMap(hit -> references(hit).stream())
                .distinct()
                .limit(20)
                .toList();

        return repository.findByChunkIds(referencedChunkIds).stream()
                .map(projection -> new RetrievalHit(
                        RetrievalType.REFERENCE,
                        projection.documentId(),
                        projection.chunkId(),
                        projection.text(),
                        Map.of(
                                "language", projection.language(),
                                "sectionPath", projection.sectionPath() == null
                                        ? ""
                                        : projection.sectionPath(),
                                "expansion", "reference"
                        )
                ))
                .toList();
    }

    private List<String> references(RetrievalHit hit) {
        Object value = hit.metadata().get("references");
        if (!(value instanceof String references) || references.isBlank()) {
            return List.of();
        }
        return List.of(references.split(",")).stream()
                .map(String::trim)
                .filter(reference -> !reference.isBlank())
                .toList();
    }
}
