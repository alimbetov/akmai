package kz.alimbetov.akmai.knowledge.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalRequestFingerprintTest {

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
}
