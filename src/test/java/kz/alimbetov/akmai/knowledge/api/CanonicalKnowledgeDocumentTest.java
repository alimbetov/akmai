package kz.alimbetov.akmai.knowledge.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalKnowledgeDocumentTest {

    private static final String CONTENT_HASH = "sha256:" + "a".repeat(64);

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> document(2, "1", "1", CONTENT_HASH, blocks(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsSourceVersionDifferentFromDocumentVersion() {
        assertThatThrownBy(() -> document(1, "2", "1", CONTENT_HASH, blocks(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source.sourceVersion");
    }

    @Test
    void rejectsMalformedContentHash() {
        assertThatThrownBy(() -> document(1, "1", "1", "sha256:not-a-digest", blocks(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 hex");
    }

    @Test
    void rejectsUrlAsStorageObjectKey() {
        assertThatThrownBy(() -> new CanonicalKnowledgeDocument.StorageReference(
                "rustfs",
                "knowledge",
                "https://storage.local/object?signature=secret",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a URL");
    }

    @Test
    void rejectsDuplicateBlockIdsBeforeIngestionWork() {
        List<CanonicalKnowledgeDocument.Block> duplicate = List.of(
                block("b-1", "First"),
                block("b-1", "Second")
        );

        assertThatThrownBy(() -> document(1, "1", "1", CONTENT_HASH, duplicate, Map.of()))
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

    @Test
    void rejectsCredentialBearingMetadataBeforeIngestion() {
        assertThatThrownBy(() -> document(
                1,
                "1",
                "1",
                CONTENT_HASH,
                blocks(),
                Map.of("authorization", "Bearer secret-token")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials or signed URLs");
    }

    @Test
    void rejectsNestedPresignedUrlBeforeIngestion() {
        assertThatThrownBy(() -> document(
                1,
                "1",
                "1",
                CONTENT_HASH,
                blocks(),
                Map.of(
                        "sourceInfo",
                        Map.of(
                                "url",
                                "https://storage.local/object?X-Amz-Signature=secret"
                        )
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials or signed URLs");
    }

    private CanonicalKnowledgeDocument document(
            int schemaVersion,
            String version,
            String sourceVersion,
            String contentHash,
            List<CanonicalKnowledgeDocument.Block> blocks,
            Map<String, Object> metadata
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
                        contentHash,
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
                metadata
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
