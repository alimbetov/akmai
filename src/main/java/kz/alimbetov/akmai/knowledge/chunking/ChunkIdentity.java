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
    private static final int CHILD_VERSION = 1;

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
            return "c2_" + digest(buffer.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot build chunk identity", exception);
        }
    }

    public String createChild(
            String documentId,
            String parentChunkId,
            int childIndex,
            String sectionPath,
            String normalizedText
    ) {
        if (childIndex < 0) {
            throw new IllegalArgumentException("childIndex must not be negative");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeByte(CHILD_VERSION);
                writeString(out, "child");
                writeString(out, documentId);
                writeString(out, parentChunkId);
                out.writeInt(childIndex);
                writeString(out, sectionPath);
                writeString(out, normalizedText);
            }
            return "cc1_" + digest(buffer.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot build child chunk identity",
                    exception
            );
        }
    }

    private String digest(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest);
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
