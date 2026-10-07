package kz.alimbetov.akmai.rag.quality;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public record RagBenchmarkDataset(
        String benchmarkVersion,
        String corpusVersion,
        boolean releaseQualified,
        List<Document> documents,
        List<Query> queries,
        Map<String, String> metadata
) {
    public RagBenchmarkDataset {
        documents = documents == null ? List.of() : List.copyOf(documents);
        queries = queries == null ? List.of() : List.copyOf(queries);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record Document(
            String id,
            String title,
            String text,
            String source,
            String language,
            KnowledgeDomain domain,
            long accessLevel,
            Map<String, Object> metadata
    ) {
        public Document {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record Query(
            String id,
            String language,
            KnowledgeDomain domain,
            QueryClass queryClass,
            Difficulty difficulty,
            String question,
            boolean answerable,
            List<String> relevantDocumentIds,
            List<String> relevantChunkIds,
            List<String> forbiddenChunkIds
    ) {
        public Query {
            relevantDocumentIds = relevantDocumentIds == null
                    ? List.of()
                    : List.copyOf(relevantDocumentIds);
            relevantChunkIds = relevantChunkIds == null
                    ? List.of()
                    : List.copyOf(relevantChunkIds);
            forbiddenChunkIds = forbiddenChunkIds == null
                    ? List.of()
                    : List.copyOf(forbiddenChunkIds);
        }
    }

    public enum QueryClass {
        FACTUAL,
        PARAPHRASE,
        LEXICAL_EXACT,
        IDENTIFIER_ONLY,
        IDENTIFIER_SEMANTIC,
        REFERENCE,
        NUMERIC,
        TEMPORAL,
        MULTI_INTENT,
        COMPARISON,
        CROSS_LANGUAGE,
        UNANSWERABLE
    }

    public enum Difficulty {
        EASY,
        MEDIUM,
        HARD
    }
}
