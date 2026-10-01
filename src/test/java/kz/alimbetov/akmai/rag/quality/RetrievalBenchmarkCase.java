package kz.alimbetov.akmai.rag.quality;

import java.util.Set;

public record RetrievalBenchmarkCase(
        String id,
        String language,
        String question,
        Set<String> relevantChunkIds
) {
}
