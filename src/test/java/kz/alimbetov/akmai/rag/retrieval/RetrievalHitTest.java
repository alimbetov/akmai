package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalHitTest {

    @Test
    void metadataCanonicalizationDropsNullKeysAndValues() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "doc.md");
        metadata.put("nullable", null);
        metadata.put(null, "bad-key");

        RetrievalHit hit = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc",
                "chunk",
                "text",
                metadata
        );

        assertThat(hit.metadata())
                .containsExactly(Map.entry("source", "doc.md"));
    }
}
