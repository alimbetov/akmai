package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;

public interface SearchProjectionRepository {

    void saveAll(List<SearchProjection> projections);

    List<String> findChunkIdsByDocumentId(String documentId);

    List<String> findChunkIds(String documentId, long generation);

    void deleteByDocumentId(String documentId);

    void deleteGeneration(String documentId, long generation);

    List<SearchProjection> findByChunkIds(List<String> chunkIds);

    List<SearchProjection> findByDocumentAndChunkIds(
            String documentId,
            List<String> chunkIds
    );

    List<SearchProjection> findAdjacent(String documentId, int chunkIndex, int radius);

    List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            int limit
    );
}
