package kz.alimbetov.akmai.rag.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QueryExpansionService {

    private final QueryChunker queryChunker;
    private final MultiQueryGenerator multiQueryGenerator;
    private final AdvancedRetrievalProperties properties;

    public QueryExpansionService(
            QueryChunker queryChunker,
            MultiQueryGenerator multiQueryGenerator,
            AdvancedRetrievalProperties properties
    ) {
        this.queryChunker = queryChunker;
        this.multiQueryGenerator = multiQueryGenerator;
        this.properties = properties;
    }

    public List<QueryChunk> expand(String question) {
        Map<String, QueryChunk> unique = new LinkedHashMap<>();
        addChunks(unique, question, QueryOrigin.ORIGINAL);

        for (String variant : multiQueryGenerator.generate(question)) {
            if (unique.size() >= properties.maxExpandedChunks()) {
                break;
            }
            addChunks(unique, variant, QueryOrigin.MULTI_QUERY);
        }
        return List.copyOf(unique.values());
    }

    private void addChunks(
            Map<String, QueryChunk> unique,
            String query,
            QueryOrigin origin
    ) {
        if (query == null || query.isBlank()) {
            return;
        }
        for (QueryChunk chunk : queryChunker.chunk(query, origin)) {
            unique.putIfAbsent(key(chunk), chunk);
            if (unique.size() >= properties.maxExpandedChunks()) {
                return;
            }
        }
    }

    private String key(QueryChunk chunk) {
        return chunk.normalizedText()
                + "|"
                + chunk.semanticText()
                + "|"
                + chunk.language();
    }
}
