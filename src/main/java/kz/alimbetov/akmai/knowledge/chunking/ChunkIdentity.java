package kz.alimbetov.akmai.knowledge.chunking;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ChunkIdentity {

    private static final int VERSION = 2;

    public String create(
            String documentId,
            int chunkIndex,
            String sectionPath,
            String normalizedText
    ) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeByte(VERSION);
                writeString(out, documentId);
                out.writeLong(chunkIndex);
                writeString(out, sectionPath);
                writeString(out, normalizedText);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(buffer.toByteArray());
            return "c2_" + HexFormat.of().formatHex(digest);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot build chunk identity", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void writeString(DataOutputStream out, String value) throws IOException {
        String normalized = Normalizer.normalize(
                value == null ? "" : value,
                Normalizer.Form.NFC
        );
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
