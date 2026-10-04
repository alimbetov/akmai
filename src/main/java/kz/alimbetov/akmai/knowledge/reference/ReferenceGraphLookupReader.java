package kz.alimbetov.akmai.knowledge.reference;

import java.util.List;
import java.util.Set;

public interface ReferenceGraphLookupReader {

    List<String> resolveSameDocumentTargets(
            String documentId,
            long generation,
            List<String> sourceChunkIds,
            Set<Long> accessLevels,
            int limit
    );
}
