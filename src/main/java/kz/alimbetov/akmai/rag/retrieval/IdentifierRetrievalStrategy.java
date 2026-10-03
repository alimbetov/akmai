package kz.alimbetov.akmai.rag.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchQuery;
import kz.alimbetov.akmai.knowledge.identifier.search.MatchMode;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class IdentifierRetrievalStrategy implements RetrievalStrategy {

    private final IdentifierSearchIndex searchIndex;
    private final PublishedSearchProjectionReader projectionRepository;
    private final RetrievalProperties properties;

    public IdentifierRetrievalStrategy(
            IdentifierSearchIndex searchIndex,
            PublishedSearchProjectionReader projectionRepository,
            RetrievalProperties properties
    ) {
        this.searchIndex = searchIndex;
        this.projectionRepository = projectionRepository;
        this.properties = properties;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.IDENTIFIER;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        LinkedHashMap<ProjectionKey, DocumentIdentifier> identifiers =
                new LinkedHashMap<>();

        queryChunk.identifiers().forEach(identifier ->
                searchIndex.search(new IdentifierSearchQuery(
                                identifier.type(),
                                identifier.normalizedValue(),
                                MatchMode.EXACT,
                                properties.identifierLimit(),
                                context.accessLevels()
                        ))
                        .forEach(hit ->
                                identifiers.putIfAbsent(key(hit), hit)
                        )
        );

        if (identifiers.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<ProjectionKey, SearchProjection> projections =
                new LinkedHashMap<>();

        identifiers.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        value -> new DocumentGeneration(
                                effectiveAccessLevel(
                                        value,
                                        context.accessLevels()
                                ),
                                value.documentId(),
                                value.generation()
                        ),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                DocumentIdentifier::chunkId,
                                java.util.stream.Collectors.toList()
                        )
                ))
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey().accessLevel() > 0)
                .forEach(entry -> {
                    DocumentGeneration scope = entry.getKey();
                    projectionRepository
                            .findByDocumentGenerationAndChunkIds(
                                    scope.documentId(),
                                    scope.generation(),
                                    entry.getValue(),
                                    Set.of(scope.accessLevel())
                            )
                            .forEach(projection ->
                                    projections.put(
                                            key(projection),
                                            projection
                                    )
                            );
                });

        return identifiers.values().stream()
                .map(identifier -> canonicalHit(
                        identifier,
                        projections.get(key(identifier))
                ))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private RetrievalHit canonicalHit(
            DocumentIdentifier identifier,
            SearchProjection projection
    ) {
        if (projection == null) {
            return null;
        }

        Map<String, Object> metadata =
                new LinkedHashMap<>(projection.metadata());
        metadata.put("identifierType", identifier.type().name());
        metadata.put("identifier", identifier.rawValue());
        metadata.put("pageNumber", identifier.pageNumber());
        metadata.put("language", projection.language());
        metadata.put(
                "sectionPath",
                projection.sectionPath() == null
                        ? ""
                        : projection.sectionPath()
        );
        metadata.put("generation", projection.generation());
        metadata.put("authorityTier", 0);
        metadata.put("authority", "EXACT_IDENTIFIER");

        return new RetrievalHit(
                RetrievalType.IDENTIFIER,
                projection.accessLevel(),
                projection.documentId(),
                projection.generation(),
                projection.chunkId(),
                projection.text(),
                metadata
        );
    }

    private ProjectionKey key(DocumentIdentifier value) {
        return new ProjectionKey(
                value.accessLevel(),
                value.documentId(),
                value.generation(),
                value.chunkId()
        );
    }

    private ProjectionKey key(SearchProjection value) {
        return new ProjectionKey(
                value.accessLevel(),
                value.documentId(),
                value.generation(),
                value.chunkId()
        );
    }

    private long effectiveAccessLevel(
            DocumentIdentifier identifier,
            Set<Long> allowed
    ) {
        if (identifier.accessLevel() > 0) {
            return allowed.contains(identifier.accessLevel())
                    ? identifier.accessLevel()
                    : 0L;
        }
        return allowed.size() == 1
                ? allowed.iterator().next()
                : 0L;
    }

    private record DocumentGeneration(
            long accessLevel,
            String documentId,
            long generation
    ) {
    }

    private record ProjectionKey(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
    }
}
