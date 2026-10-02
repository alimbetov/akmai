package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;

public interface SearchProjectionRepository {

    void saveAll(List<SearchProjection> projections);

    List<String> findChunkIds(String documentId, long generation);

    List<SearchProjection> findGeneration(String documentId, long generation);

    void deleteByDocumentId(String documentId);

    void deleteGeneration(String documentId, long generation);
}
