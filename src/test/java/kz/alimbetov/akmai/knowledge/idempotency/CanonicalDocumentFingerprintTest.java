package kz.alimbetov.akmai.knowledge.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalDocumentFingerprintTest {

    private final CanonicalRequestFingerprint fingerprint =
            new CanonicalRequestFingerprint(new ObjectMapper());

    @Test
    void pageOrBlockProvenanceChangeChangesFingerprint() {
        CanonicalDocument page7 = document(7, "block-1");
        CanonicalDocument page8 = document(8, "block-1");
        CanonicalDocument anotherBlock = document(7, "block-2");

        assertThat(fingerprint.fingerprint(page7))
                .isNotEqualTo(fingerprint.fingerprint(page8));
        assertThat(fingerprint.fingerprint(page7))
                .isNotEqualTo(fingerprint.fingerprint(anotherBlock));
    }

    @Test
    void sameCanonicalDocumentProducesStableFingerprint() {
        assertThat(fingerprint.fingerprint(document(7, "block-1")))
                .isEqualTo(fingerprint.fingerprint(document(7, "block-1"))));
    }

    private CanonicalDocument document(int page, String blockId) {
        return new CanonicalDocument(
                "doc-1",
                "v1",
                "Document",
                "s3://bucket/doc-1.pdf",
                "en",
                KnowledgeDomain.TECHNICAL,
                1L,
                List.of(new CanonicalDocument.Block(
                        blockId,
                        CanonicalDocument.BlockType.PARAGRAPH,
                        "The service returns HTTP 409 for a duplicate operation.",
                        null,
                        page,
                        page,
                        "API > POST /payments",
                        null
                )),
                Map.of("fileId", "file-1")
        );
    }
}
