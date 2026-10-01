package kz.alimbetov.akmai.knowledge.chunking;

import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingTextBuilder {

    public String build(
            KnowledgeDocument document,
            String sectionPath,
            String chunkText
    ) {
        return """
                Document: %s
                Domain: %s
                Language: %s
                Section: %s

                %s
                """.formatted(
                document.title(),
                document.domain(),
                document.language(),
                sectionPath,
                chunkText
        ).trim();
    }
}
