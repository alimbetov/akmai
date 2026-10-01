package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {

    @Test
    void exposesOnlyWhitelistedProvenanceMetadata() {
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                "chunk",
                "canonical text",
                Map.of(
                        "source", "law.md",
                        "language", "ru",
                        "sectionPath", "Article 1",
                        "secret", "must-not-leak"
                )
        );

        String context = new ContextAssembler().assemble(List.of(hit));

        assertThat(context)
                .contains("[SOURCE 1]")
                .contains("source: law.md")
                .contains("canonical text")
                .doesNotContain("must-not-leak")
                .doesNotContain("secret");
    }
}
