package kz.alimbetov.akmai.rag.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class ReferenceRetrievalStrategy implements RetrievalStrategy {

    private static final int MAX_REFERENCES = 20;

    private final SearchProjectionRepository repository;
    private final IdentifierSearchIndex identifierSearchIndex;

    public ReferenceRetrievalStrategy(
            SearchProjectionRepository repository,
            IdentifierSearchIndex identifierSearchIndex
    ) {
        this.repository = repository;
        this.identifierSearchIndex = identifierSearchIndex;
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
        List<String> seedIds = context.dependencyHits().stream()
                .map(RetrievalHit::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Set<String> seedIdSet = Set.copyOf(seedIds);

        LinkedHashMap<String, DocumentIdentifier> resolved = new LinkedHashMap<>();
        repository.findByChunkIds(seedIds).stream()
                .flatMap(seed -> seed.references().stream())
                .distinct()
                .limit(MAX_REFERENCES)
                .flatMap(reference -> identifierSearchIndex.search(reference, 10).stream())
                .filter(identifier -> !seedIdSet.contains(identifier.chunkId()))
                .forEach(identifier -> resolved.putIfAbsent(
                        identifier.chunkId(),
                        identifier
                ));

        if (resolved.isEmpty()) {
            return List.of();
        }

        Map<String, SearchProjection> targets = new LinkedHashMap<>();
        repository.findByChunkIds(List.copyOf(resolved.keySet()))
                .forEach(projection -> targets.putIfAbsent(
                        projection.chunkId(),
                        projection
                ));

        return resolved.entrySet().stream()
                .map(entry -> targetHit(entry.getValue(), targets.get(entry.getKey())))
                .filter(hit -> hit != null)
                .toList();
    }

    private RetrievalHit targetHit(
            DocumentIdentifier identifier,
            SearchProjection projection
    ) {
        if (projection == null) {
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>(projection.metadata());
        metadata.put("identifierType", identifier.type().name());
        metadata.put("identifier", identifier.rawValue());
        metadata.put("pageNumber", identifier.pageNumber());
        metadata.put("language", projection.language());
        metadata.put("sectionPath", projection.sectionPath() == null
                ? ""
                : projection.sectionPath());
        metadata.put("expansion", "reference");

        return new RetrievalHit(
                RetrievalType.REFERENCE,
                projection.documentId(),
                projection.chunkId(),
                projection.text(),
                metadata
        );
    }
}
