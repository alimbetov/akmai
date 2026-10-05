package kz.alimbetov.akmai.rag.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Final lifecycle/ACL/TTL fence immediately before retrieved context is
 * exposed to answer generation.
 */
@Component
public class PublishedContextRevalidator {

    private final PublishedSearchProjectionReader projectionReader;
    private final PublishedLifecycleEligibility lifecycleEligibility;

    @Autowired
    public PublishedContextRevalidator(
            PublishedSearchProjectionReader projectionReader,
            PublishedLifecycleEligibility lifecycleEligibility
    ) {
        this.projectionReader = projectionReader;
        this.lifecycleEligibility = lifecycleEligibility;
    }

    PublishedContextRevalidator(
            PublishedSearchProjectionReader projectionReader
    ) {
        this(projectionReader, PublishedLifecycleEligibility.allowAll());
    }

    public List<RetrievalHit> revalidate(
            List<RetrievalHit> hits,
            Set<Long> accessLevels
    ) {
        requireAccessLevels(accessLevels);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<RetrievalHit> lifecycleEligible = lifecycleEligibility.filter(
                hits,
                accessLevels
        );
        if (lifecycleEligible.isEmpty()) {
            return List.of();
        }

        List<RetrievalHit> eligible = lifecycleEligible.stream()
                .filter(RetrievalHit::hasRoutingIdentity)
                .filter(hit -> accessLevels.contains(hit.accessLevel()))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }

        List<PublishedSearchProjectionReader.ProjectionKey> keys =
                eligible.stream()
                        .map(hit ->
                                new PublishedSearchProjectionReader.ProjectionKey(
                                        hit.accessLevel(),
                                        hit.documentId(),
                                        hit.generation(),
                                        hit.chunkId()
                                )
                        )
                        .distinct()
                        .toList();

        Set<Key> published = projectionReader
                .findPublishedByKeys(keys, accessLevels)
                .stream()
                .map(Key::of)
                .collect(
                        java.util.stream.Collectors.toCollection(
                                LinkedHashSet::new
                        )
                );

        return eligible.stream()
                .filter(hit -> published.contains(Key.of(hit)))
                .toList();
    }

    private void requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        if (accessLevels.stream()
                .anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "accessLevels must contain positive values"
            );
        }
    }

    private record Key(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
        static Key of(RetrievalHit hit) {
            return new Key(
                    hit.accessLevel(),
                    hit.documentId(),
                    hit.generation(),
                    hit.chunkId()
            );
        }

        static Key of(SearchProjection projection) {
            return new Key(
                    projection.accessLevel(),
                    projection.documentId(),
                    projection.generation(),
                    projection.chunkId()
            );
        }
    }
}
