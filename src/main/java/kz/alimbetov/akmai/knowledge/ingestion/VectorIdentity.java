package kz.alimbetov.akmai.knowledge.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.UUID;

public final class VectorIdentity {

    public static final short VERSION = 2;

    private VectorIdentity() {
    }

    public static String physicalId(
            String documentId,
            long generation,
            String chunkId
    ) {
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be > 0");
        }
        byte[] digest = sha256(canonicalBytes(documentId, generation, chunkId));
        byte[] uuidBytes = new byte[16];
        System.arraycopy(digest, 0, uuidBytes, 0, uuidBytes.length);

        // RFC 4122 variant and UUID version 8 (custom/application defined).
        uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0f) | 0x80);
        uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3f) | 0x80);

        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (uuidBytes[i] & 0xffL);
        }
        for (int i = 8; i < 16; i++) {
            least = (least << 8) | (uuidBytes[i] & 0xffL);
        }
        return new UUID(most, least).toString();
    }

    private static byte[] canonicalBytes(
            String documentId,
            long generation,
            String chunkId
    ) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(VERSION);
                writeString(out, documentId);
                out.writeLong(generation);
                writeString(out, chunkId);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot build vector identity", exception);
        }
    }

    private static void writeString(DataOutputStream out, String value)
            throws IOException {
        String normalized = Normalizer.normalize(
                value == null ? "" : value,
                Normalizer.Form.NFC
        );
        byte[] raw = normalized.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
