package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;

public interface SearchProjectionRepository {

    void saveAll(List<SearchProjection> projections);

    void saveAll(
            GenerationIdentity identity,
            List<SearchProjection> projections
    );

    List<String> findChunkIds(String documentId, long generation);

    List<SearchProjection> findGeneration(String documentId, long generation);

    List<SearchProjection> findGeneration(GenerationIdentity identity);

    void deleteByDocumentId(String documentId);

    void deleteGeneration(String documentId, long generation);

    void deleteGeneration(GenerationIdentity identity);

    int deleteGenerationCount(GenerationIdentity identity);
}
