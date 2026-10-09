package kz.alimbetov.akmai.knowledge.ingestion.async;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import org.springframework.stereotype.Component;

@Component
public class AsyncIngestionJobFingerprint {

    public String fingerprint(
            int schemaVersion,
            String documentId,
            long accessLevel,
            CanonicalKnowledgeDocument document,
            String canonicalHash
    ) {
        if (document == null) {
            throw new IllegalArgumentException("canonicalDocument is required");
        }
        String source = String.join(
                "\u001f",
                Integer.toString(schemaVersion),
                text(documentId),
                Long.toString(accessLevel),
                document.source().type().name(),
                text(document.source().fileId()),
                text(document.source().sourceVersion()),
                text(document.source().contentHash()),
                text(canonicalHash)
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
