package kz.alimbetov.akmai.knowledge.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalKnowledgeDocumentTest {

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> document(2, "1", "1", blocks()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsSourceVersionDifferentFromDocumentVersion() {
        assertThatThrownBy(() -> document(1, "2", "1", blocks()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source.sourceVersion");
    }

    @Test
    void rejectsDuplicateBlockIdsBeforeIngestionWork() {
        List<CanonicalKnowledgeDocument.Block> duplicate = List.of(
                block("b-1", "First"),
                block("b-1", "Second")
        );

        assertThatThrownBy(() -> document(1, "1", "1", duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate canonical blockId");
    }

    @Test
    void rejectsInvalidPageRange() {
        assertThatThrownBy(() -> new CanonicalKnowledgeDocument.Block(
                "b-1",
                CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                "text",
                null,
                4,
                3,
                List.of("Section"),
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageTo");
    }

    private CanonicalKnowledgeDocument document(
            int schemaVersion,
            String version,
            String sourceVersion,
            List<CanonicalKnowledgeDocument.Block> blocks
    ) {
        return new CanonicalKnowledgeDocument(
                schemaVersion,
                "doc-1",
                version,
                "Document",
                "en",
                KnowledgeDomain.TECHNICAL,
                1L,
                new CanonicalKnowledgeDocument.Source(
                        CanonicalKnowledgeDocument.SourceType.FILE,
                        "file-1",
                        sourceVersion,
                        "document.pdf",
                        "application/pdf",
                        "sha256:source",
                        new CanonicalKnowledgeDocument.StorageReference(
                                "rustfs",
                                "knowledge",
                                "tenant/file-1.pdf",
                                null
                        )
                ),
                new CanonicalKnowledgeDocument.Processing(
                        "pdf-parser",
                        "1.0.0",
                        Instant.parse("2026-10-09T00:00:00Z")
                ),
                blocks,
                Map.of()
        );
    }

    private List<CanonicalKnowledgeDocument.Block> blocks() {
        return List.of(block("b-1", "Text"));
    }

    private CanonicalKnowledgeDocument.Block block(String id, String text) {
        return new CanonicalKnowledgeDocument.Block(
                id,
                CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                text,
                null,
                1,
                1,
                List.of("Section"),
                null
        );
    }
}
