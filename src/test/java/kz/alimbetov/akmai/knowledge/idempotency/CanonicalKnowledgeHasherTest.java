package kz.alimbetov.akmai.knowledge.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalKnowledgeHasherTest {

    private static final String CONTENT_HASH = "sha256:" + "a".repeat(64);
    private final CanonicalKnowledgeHasher hasher =
            new CanonicalKnowledgeHasher(new ObjectMapper());

    @Test
    void ignoresOperationalStorageAndParseTimestamp() {
        CanonicalKnowledgeDocument first = document(
                "bucket-a",
                "object-a",
                Instant.parse("2026-10-09T00:00:00Z"),
                "Stable content"
        );
        CanonicalKnowledgeDocument second = document(
                "bucket-b",
                "object-b",
                Instant.parse("2026-10-09T03:00:00Z"),
                "Stable content"
        );

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
    }

    @Test
    void changesWhenCanonicalContentChanges() {
        String first = hasher.hash(document(
                "bucket",
                "object",
                Instant.parse("2026-10-09T00:00:00Z"),
                "Stable content"
        ));
        String second = hasher.hash(document(
                "bucket",
                "object",
                Instant.parse("2026-10-09T00:00:00Z"),
                "Changed content"
        ));

        assertThat(first).isNotEqualTo(second);
    }

    private CanonicalKnowledgeDocument document(
            String bucket,
            String objectKey,
            Instant parsedAt,
            String text
    ) {
        return new CanonicalKnowledgeDocument(
                1,
                "doc-1",
                "1",
                "Document",
                "en",
                KnowledgeDomain.TECHNICAL,
                1L,
                new CanonicalKnowledgeDocument.Source(
                        CanonicalKnowledgeDocument.SourceType.FILE,
                        "file-1",
                        "1",
                        "document.pdf",
                        "application/pdf",
                        CONTENT_HASH,
                        new CanonicalKnowledgeDocument.StorageReference(
                                "rustfs",
                                bucket,
                                objectKey,
                                null
                        )
                ),
                new CanonicalKnowledgeDocument.Processing(
                        "pdf-parser",
                        "1.0.0",
                        parsedAt
                ),
                List.of(new CanonicalKnowledgeDocument.Block(
                        "b-1",
                        CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                        text,
                        null,
                        1,
                        1,
                        List.of("Section"),
                        null
                )),
                Map.of("stable", "metadata")
        );
    }
}
