package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

        return repository.findByChunkIds(seedIds).stream()
                .flatMap(seed -> seed.references().stream())
                .distinct()
                .limit(MAX_REFERENCES)
                .flatMap(reference -> identifierSearchIndex
                        .search(reference, 10)
                        .stream())
                .filter(identifier -> !seedIdSet.contains(identifier.chunkId()))
                .collect(Collectors.toMap(
                        identifier -> identifier.chunkId(),
                        identifier -> identifier,
                        (left, right) -> left
                ))
                .values()
                .stream()
                .map(identifier -> new RetrievalHit(
                        RetrievalType.REFERENCE,
                        identifier.documentId(),
                        identifier.chunkId(),
                        identifier.contextText(),
                        Map.of(
                                "identifierType", identifier.type().name(),
                                "identifier", identifier.rawValue(),
                                "pageNumber", identifier.pageNumber(),
                                "expansion", "reference"
                        )
                ))
                .toList();
    }
}
