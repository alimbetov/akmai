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
}
