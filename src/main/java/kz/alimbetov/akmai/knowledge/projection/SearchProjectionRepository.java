package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;

public interface SearchProjectionRepository {

    void saveAll(List<SearchProjection> projections);

    List<String> findChunkIdsByDocumentId(String documentId);

    void deleteByDocumentId(String documentId);

    List<SearchProjection> findByChunkIds(List<String> chunkIds);

    List<SearchProjection> findAdjacent(String documentId, int chunkIndex, int radius);

    List<SearchProjection> searchLexical(String query, List<String> documentIds, int limit);
}
