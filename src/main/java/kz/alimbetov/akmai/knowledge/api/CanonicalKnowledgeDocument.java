package kz.alimbetov.akmai.knowledge.api;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

/**
 * Versioned transport contract produced by FileService and consumed by AkmAI.
 * It preserves source structure and provenance while leaving RAG chunking,
 * embeddings and retrieval policy under AkmAI ownership.
 */
public record CanonicalKnowledgeDocument(
        int schemaVersion,
        String documentId,
        String version,
        String title,
        String language,
        KnowledgeDomain domain,
        long accessLevel,
        Source source,
        Processing processing,
        List<Block> blocks,
        Map<String, Object> metadata
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CanonicalKnowledgeDocument {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported canonical schemaVersion: " + schemaVersion
            );
        }
        documentId = requireText("documentId", documentId);
        version = requireText("version", version);
        title = requireText("title", title);
        language = requireText("language", language);
        if (domain == null) {
            throw new IllegalArgumentException("domain is required");
        }
        if (accessLevel <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (!version.equals(source.sourceVersion())) {
            throw new IllegalArgumentException(
                    "version must match source.sourceVersion"
            );
        }
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException(
                    "canonical document must contain blocks"
            );
        }
        HashSet<String> ids = new HashSet<>();
        for (Block block : blocks) {
            if (block == null) {
                throw new IllegalArgumentException("canonical block must not be null");
            }
            if (!ids.add(block.blockId())) {
                throw new IllegalArgumentException(
                        "duplicate canonical blockId: " + block.blockId()
                );
            }
        }
        metadata = metadata == null ? Map.of() : metadata;
        validateSafeMetadata(metadata, "metadata");
        metadata = Map.copyOf(metadata);
    }

    public record Source(
            SourceType type,
            String fileId,
            String sourceVersion,
            String fileName,
            String mediaType,
            String contentHash,
            StorageReference storage
    ) {
        public Source {
            if (type == null) {
                throw new IllegalArgumentException("source.type is required");
            }
            fileId = requireText("source.fileId", fileId);
            sourceVersion = requireText("source.sourceVersion", sourceVersion);
            fileName = requireText("source.fileName", fileName);
            mediaType = requireText("source.mediaType", mediaType);
            contentHash = requireText("source.contentHash", contentHash);
        }
    }

    public enum SourceType {
        FILE
    }

    public record StorageReference(
            String provider,
            String bucket,
            String objectKey,
            String versionId
    ) {
        public StorageReference {
            provider = requireText("storage.provider", provider);
            bucket = requireText("storage.bucket", bucket);
            objectKey = requireText("storage.objectKey", objectKey);
            versionId = normalizeOptional(versionId);
        }
    }

    public record Processing(
            String parser,
            String parserVersion,
            Instant parsedAt
    ) {
        public Processing {
            parser = requireText("processing.parser", parser);
            parserVersion = requireText(
                    "processing.parserVersion",
                    parserVersion
            );
            if (parsedAt == null) {
                throw new IllegalArgumentException("processing.parsedAt is required");
            }
        }
    }

    public record Block(
            String blockId,
            BlockType type,
            String text,
            Integer headingLevel,
            Integer pageFrom,
            Integer pageTo,
            List<String> sectionPath,
            BoundingBox boundingBox
    ) {
        public Block {
            blockId = requireText("blockId", blockId);
            if (type == null) {
                throw new IllegalArgumentException("block type is required");
            }
            text = requireText("block text", text);
            if (headingLevel != null
                    && (headingLevel < 1 || headingLevel > 7)) {
                throw new IllegalArgumentException(
                        "headingLevel must be between 1 and 7"
                );
            }
            validatePageRange(pageFrom, pageTo);
            sectionPath = normalizePath(sectionPath);
        }
    }

    public enum BlockType {
        HEADING,
        PARAGRAPH,
        TABLE,
        LIST,
        CODE,
        FOOTNOTE,
        IMAGE_TEXT
    }

    public record BoundingBox(
            double x,
            double y,
            double width,
            double height
    ) {
        public BoundingBox {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(width)
                    || !Double.isFinite(height)
                    || x < 0
                    || y < 0
                    || width < 0
                    || height < 0) {
                throw new IllegalArgumentException(
                        "bounding box values must be finite and non-negative"
                );
            }
        }
    }

    private static void validatePageRange(Integer pageFrom, Integer pageTo) {
        if (pageFrom != null && pageFrom <= 0) {
            throw new IllegalArgumentException("pageFrom must be positive");
        }
        if (pageTo != null && pageTo <= 0) {
            throw new IllegalArgumentException("pageTo must be positive");
        }
        if (pageFrom != null && pageTo != null && pageTo < pageFrom) {
            throw new IllegalArgumentException("pageTo must be >= pageFrom");
        }
    }

    private static List<String> normalizePath(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static void validateSafeMetadata(Object value, String path) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (sensitiveMetadataKey(key)) {
                    throw new IllegalArgumentException(
                            path + "." + key + " must not contain credentials or signed URLs"
                    );
                }
                validateSafeMetadata(entry.getValue(), path + "." + key);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                validateSafeMetadata(list.get(index), path + "[" + index + "]");
            }
            return;
        }
        if (value instanceof String text && sensitiveMetadataValue(text)) {
            throw new IllegalArgumentException(
                    path + " must not contain credentials or signed URLs"
            );
        }
    }

    private static boolean sensitiveMetadataKey(String key) {
        String normalized = key == null
                ? ""
                : key.replace("-", "")
                        .replace("_", "")
                        .toLowerCase(Locale.ROOT);
        return normalized.contains("presigned")
                || normalized.contains("signedurl")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("secretkey")
                || normalized.contains("accesskey")
                || normalized.contains("sessiontoken");
    }

    private static boolean sensitiveMetadataValue(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("bearer ")
                || normalized.contains("x-amz-signature=")
                || normalized.contains("x-amz-credential=")
                || normalized.contains("x-amz-security-token=")
                || normalized.contains("x-goog-signature=")
                || normalized.contains("x-goog-credential=");
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
