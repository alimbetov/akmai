package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;
import java.util.Set;

public interface PublishedSearchProjectionReader {

    List<SearchProjection> findByDocumentAndChunkIds(
            String documentId,
            List<String> chunkIds,
            Set<Long> accessLevels
    );

    List<SearchProjection> findByDocumentGenerationAndChunkIds(
            String documentId,
            long generation,
            List<String> chunkIds,
            Set<Long> accessLevels
    );

    List<SearchProjection> findPublishedByKeys(
            List<ProjectionKey> keys,
            Set<Long> accessLevels
    );

    List<SearchProjection> findAdjacent(
            String documentId,
            long generation,
            int chunkIndex,
            int radius,
            Set<Long> accessLevels
    );

    List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    );

    default List<SearchProjection> searchSemanticConcepts(
            List<String> conceptIds,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        return List.of();
    }

    record ProjectionKey(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
        public ProjectionKey {
            if (accessLevel <= 0 || generation <= 0) {
                throw new IllegalArgumentException(
                        "projection routing identity must be positive"
                );
            }
            if (documentId == null
                    || documentId.isBlank()
                    || chunkId == null
                    || chunkId.isBlank()) {
                throw new IllegalArgumentException(
                        "projection routing identity must be complete"
                );
            }
        }
    }
}
