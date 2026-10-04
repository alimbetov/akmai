package kz.alimbetov.akmai.knowledge.graph;

import java.util.List;
import java.util.Set;

public interface AdaptiveGraphLookupReader {

    List<ChunkAssociation> findRelated(
            Set<Long> allowedAccessLevels,
            ChunkGraphNode source,
            int graphVersion,
            Set<AssociationBand> bands,
            double minimumWeight,
            int limit
    );
}
