package kz.alimbetov.akmai.rag.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
        List<String> variants = Stream.concat(
                        Stream.of(question),
                        multiQueryGenerator.generate(question).stream()
                )
                .filter(value -> value != null && !value.isBlank())
                .toList();

        Map<String, QueryChunk> unique = new LinkedHashMap<>();
        for (String variant : variants) {
            for (QueryChunk chunk : queryChunker.chunk(variant)) {
                unique.putIfAbsent(key(chunk), chunk);
                if (unique.size() >= properties.maxExpandedChunks()) {
                    return List.copyOf(unique.values());
                }
            }
        }
        return List.copyOf(unique.values());
    }

    private String key(QueryChunk chunk) {
        return chunk.normalizedText()
                + "|"
                + chunk.semanticText()
                + "|"
                + chunk.language();
    }
}
