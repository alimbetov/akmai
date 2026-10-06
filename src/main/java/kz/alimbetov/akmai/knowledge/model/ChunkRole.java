package kz.alimbetov.akmai.knowledge.model;

import java.util.Locale;
import java.util.Map;

public enum ChunkRole {
    PARENT,
    CHILD;

    public static final String METADATA_KEY = "akmaiChunkRole";
    public static final String PARENT_CHUNK_ID_KEY = "akmaiParentChunkId";
    public static final String PARENT_CHUNK_INDEX_KEY = "akmaiParentChunkIndex";
    public static final String CHILD_INDEX_KEY = "akmaiChildIndex";
    public static final String CHILD_COUNT_KEY = "akmaiChildCount";
    public static final String ESTIMATED_TOKENS_KEY = "akmaiEstimatedTokens";

    public static ChunkRole fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get(METADATA_KEY);
        if (raw instanceof ChunkRole role) {
            return role;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean isParent(Map<String, Object> metadata) {
        return fromMetadata(metadata) == PARENT;
    }

    public static boolean isSearchable(Map<String, Object> metadata) {
        return fromMetadata(metadata) != PARENT;
    }
}
