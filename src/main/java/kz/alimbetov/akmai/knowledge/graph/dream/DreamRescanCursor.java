package kz.alimbetov.akmai.knowledge.graph.dream;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;

/** Durable keyset cursor for the bounded old-node rescan lane. */
public record DreamRescanCursor(
        long accessLevel,
        String documentId,
        long generation,
        String chunkId
) implements Comparable<DreamRescanCursor> {

    public DreamRescanCursor {
        new ChunkGraphNode(accessLevel, documentId, generation, chunkId);
    }

    public static DreamRescanCursor from(ChunkGraphNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        return new DreamRescanCursor(
                node.accessLevel(),
                node.documentId(),
                node.generation(),
                node.chunkId()
        );
    }

    public String encode() {
        return accessLevel
                + ":"
                + encodeText(documentId)
                + ":"
                + generation
                + ":"
                + encodeText(chunkId);
    }

    public static DreamRescanCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(":", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("invalid Dream rescan cursor");
        }
        try {
            return new DreamRescanCursor(
                    Long.parseLong(parts[0]),
                    decodeText(parts[1]),
                    Long.parseLong(parts[2]),
                    decodeText(parts[3])
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "invalid Dream rescan cursor",
                    exception
            );
        }
    }

    @Override
    public int compareTo(DreamRescanCursor other) {
        return new ChunkGraphNode(
                accessLevel,
                documentId,
                generation,
                chunkId
        ).compareTo(new ChunkGraphNode(
                other.accessLevel,
                other.documentId,
                other.generation,
                other.chunkId
        ));
    }

    private static String encodeText(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value),
                StandardCharsets.UTF_8
        );
    }
}
