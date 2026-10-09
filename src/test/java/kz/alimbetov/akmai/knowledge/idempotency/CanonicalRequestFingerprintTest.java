package kz.alimbetov.akmai.knowledge.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalRequestFingerprintTest {

    private static final String CONTENT_HASH = "sha256:" + "a".repeat(64);
    private final CanonicalRequestFingerprint fingerprint =
            new CanonicalRequestFingerprint(new ObjectMapper());

    @Test
    void accessLevelChangesLogicalRequestIdentity() {
        AddKnowledgeRequest levelOne = request(1L);
        AddKnowledgeRequest levelTwo = request(2L);

        assertThat(fingerprint.fingerprint(levelOne))
                .isNotEqualTo(fingerprint.fingerprint(levelTwo));
        assertThat(fingerprint.fingerprint(levelOne))
                .isEqualTo(fingerprint.fingerprint(request(1L)));
    }

    @Test
    void canonicalRetryIgnoresParseTimestampAndStorageLocation() {
        CanonicalKnowledgeDocument first = canonical(
                "bucket-a",
                "object-a",
                Instant.parse("2026-10-09T00:00:00Z"),
                "Stable content"
        );
        CanonicalKnowledgeDocument retry = canonical(
                "bucket-b",
                "object-b",
                Instant.parse("2026-10-09T03:00:00Z"),
                "Stable content"
        );

        assertThat(fingerprint.fingerprint(first))
                .isEqualTo(fingerprint.fingerprint(retry));
    }

    @Test
    void canonicalContentChangeChangesRequestIdentity() {
        assertThat(fingerprint.fingerprint(canonical(
                "bucket",
                "object",
                Instant.parse("2026-10-09T00:00:00Z"),
                "Stable content"
        ))).isNotEqualTo(fingerprint.fingerprint(canonical(
                "bucket",
                "object",
                Instant.parse("2026-10-09T00:00:00Z"),
                "Changed content"
        )));
    }

    private AddKnowledgeRequest request(long accessLevel) {
        return new AddKnowledgeRequest(
                "doc-1",
                "Title",
                "Text",
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                accessLevel,
                Map.of("version", "1")
        );
    }

    private CanonicalKnowledgeDocument canonical(
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
