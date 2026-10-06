package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.ParentChildProperties;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.springframework.stereotype.Component;

@Component
public class ParentContextExpansion {

    private static final String EXPANSION_KEY = "akmaiParentExpansion";
    private static final String MATCHED_CHILD_KEY = "akmaiMatchedChildChunkId";

    private final PublishedSearchProjectionReader repository;
    private final ParentChildProperties properties;

    public ParentContextExpansion(
            PublishedSearchProjectionReader repository,
            ParentChildProperties properties
    ) {
        this.repository = repository;
        this.properties = properties;
    }

    public List<RetrievalHit> expand(
            List<RetrievalHit> hits,
            Set<Long> accessLevels
    ) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        if (!properties.enabled() || !properties.expansionEnabled()) {
            return List.copyOf(hits);
        }
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }

        List<ParentRequest> requests = parentRequests(hits, accessLevels);
        if (requests.isEmpty()) {
            return List.copyOf(hits);
        }

        List<PublishedSearchProjectionReader.ProjectionKey> keys = requests.stream()
                .map(ParentRequest::key)
                .distinct()
                .toList();
        List<SearchProjection> parents = repository.findPublishedByKeys(
                keys,
                accessLevels
        );
        Map<String, SearchProjection> parentByKey = new HashMap<>();
        for (SearchProjection parent : parents) {
            if (parent == null) {
                continue;
            }
            parentByKey.put(
                    key(
                            parent.accessLevel(),
                            parent.documentId(),
                            parent.generation(),
                            parent.chunkId()
                    ),
                    parent
            );
        }

        Map<String, String> parentByChild = new HashMap<>();
        requests.forEach(request -> parentByChild.put(
                childKey(request.hit()),
                parentKey(request.key())
        ));

        Set<String> emittedParents = new HashSet<>();
        List<RetrievalHit> result = new ArrayList<>(hits.size());
        for (RetrievalHit hit : hits) {
            String requestedParent = parentByChild.get(childKey(hit));
            if (requestedParent == null) {
                result.add(hit);
                continue;
            }

            SearchProjection parent = parentByKey.get(requestedParent);
            if (parent == null) {
                result.add(hit);
                continue;
            }
            if (!emittedParents.add(requestedParent)) {
                continue;
            }
            result.add(parent(hit, parent));
        }

        return List.copyOf(result);
    }

    private List<ParentRequest> parentRequests(
            List<RetrievalHit> hits,
            Set<Long> accessLevels
    ) {
        List<ParentRequest> requests = new ArrayList<>();
        Set<String> uniqueParents = new HashSet<>();

        for (RetrievalHit hit : hits) {
            if (hit == null
                    || !hit.hasRoutingIdentity()
                    || !accessLevels.contains(hit.accessLevel())) {
                continue;
            }
            String parentChunkId = parentChunkId(hit);
            if (parentChunkId == null) {
                continue;
            }

            PublishedSearchProjectionReader.ProjectionKey parentKey =
                    new PublishedSearchProjectionReader.ProjectionKey(
                            hit.accessLevel(),
                            hit.documentId(),
                            hit.generation(),
                            parentChunkId
                    );
            String canonicalParent = parentKey(parentKey);
            if (!uniqueParents.contains(canonicalParent)) {
                if (uniqueParents.size()
                        >= properties.maxParentExpansions()) {
                    continue;
                }
                uniqueParents.add(canonicalParent);
            }
            requests.add(new ParentRequest(hit, parentKey));
        }

        return List.copyOf(requests);
    }

    private RetrievalHit parent(
            RetrievalHit seed,
            SearchProjection parent
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(parent.metadata());
        metadata.put("language", parent.language());
        metadata.put("domain", parent.domain().name());
        metadata.put(
                "sectionPath",
                parent.sectionPath() == null ? "" : parent.sectionPath()
        );
        metadata.put("chunkIndex", parent.chunkIndex());
        metadata.put("generation", parent.generation());
        metadata.put("accessLevel", parent.accessLevel());
        metadata.put(ChunkRole.METADATA_KEY, ChunkRole.PARENT.name());
        metadata.put(EXPANSION_KEY, true);
        metadata.put(MATCHED_CHILD_KEY, seed.chunkId());

        return new RetrievalHit(
                seed.type(),
                parent.accessLevel(),
                parent.documentId(),
                parent.generation(),
                parent.chunkId(),
                parent.text(),
                Map.copyOf(metadata),
                seed.evidence(),
                seed.fusedScore()
        );
    }

    private String parentChunkId(RetrievalHit hit) {
        Object value = hit.metadata().get(ChunkRole.PARENT_CHUNK_ID_KEY);
        if (value instanceof String parent && !parent.isBlank()) {
            return parent;
        }
        return null;
    }

    private String childKey(RetrievalHit hit) {
        if (hit == null) {
            return "";
        }
        return key(
                hit.accessLevel(),
                hit.documentId(),
                hit.generation(),
                hit.chunkId()
        );
    }

    private String parentKey(
            PublishedSearchProjectionReader.ProjectionKey value
    ) {
        return key(
                value.accessLevel(),
                value.documentId(),
                value.generation(),
                value.chunkId()
        );
    }

    private String key(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
        return accessLevel
                + "\u0000"
                + String.valueOf(documentId)
                + "\u0000"
                + generation
                + "\u0000"
                + String.valueOf(chunkId);
    }

    private record ParentRequest(
            RetrievalHit hit,
            PublishedSearchProjectionReader.ProjectionKey key
    ) {
    }
}
