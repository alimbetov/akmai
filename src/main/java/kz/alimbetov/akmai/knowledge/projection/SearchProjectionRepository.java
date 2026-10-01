package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;

public interface SearchProjectionRepository {

    void saveAll(List<SearchProjection> projections);

    List<String> findChunkIdsByDocumentId(String documentId);

    void deleteByDocumentId(String documentId);

    List<SearchProjection> searchLexical(String query, List<String> documentIds, int limit);
}
