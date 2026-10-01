package kz.alimbetov.akmai.knowledge.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ChunkIdentity {

    public String create(
            String documentId,
            int chunkIndex,
            String sectionPath,
            String normalizedText
    ) {
        String canonical = String.join(
                "\n",
                documentId,
                Integer.toString(chunkIndex),
                sectionPath == null ? "" : sectionPath,
                normalizedText
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
