package kz.alimbetov.akmai.rag.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ContextAssembler {

    private final ObjectMapper objectMapper;

    public ContextAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String assemble(List<RetrievalHit> hits) {
        List<ContextSource> sources = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHit hit = hits.get(index);
            SourceRef source = SourceRef.from(index + 1, hit);
            sources.add(new ContextSource(
                    source.number(),
                    source.documentId(),
                    source.chunkId(),
                    source.source(),
                    source.language(),
                    source.sectionPath(),
                    source.page(),
                    hit.text()
            ));
        }
        try {
            return objectMapper.writeValueAsString(
                    new ContextEnvelope(List.copyOf(sources))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot serialize RAG context envelope",
                    exception
            );
        }
    }

    public record ContextEnvelope(List<ContextSource> sources) {
    }

    public record ContextSource(
            int sourceNumber,
            String documentId,
            String chunkId,
            String source,
            String language,
            String sectionPath,
            String page,
            String text
    ) {
    }
}
