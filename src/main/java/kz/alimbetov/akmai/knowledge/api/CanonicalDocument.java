package kz.alimbetov.akmai.knowledge.api;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

/**
 * Transport-neutral parsed-document contract for a future FileService/Inbox
 * integration. Blocks preserve source provenance separately from RAG chunks;
 * AkmAI remains responsible for semantic chunking.
 */
public record CanonicalDocument(
        String documentId,
        String version,
        String title,
        String source,
        String language,
        KnowledgeDomain domain,
        long accessLevel,
        List<Block> blocks,
        Map<String, Object> metadata
) {
    public CanonicalDocument {
        requireText("documentId", documentId);
        requireText("version", version);
        requireText("title", title);
        requireText("source", source);
        requireText("language", language);
        if (domain == null) {
            throw new IllegalArgumentException("domain is required");
        }
        if (accessLevel <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("canonical document must contain blocks");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record Block(
            String blockId,
            BlockType type,
            String text,
            Integer headingLevel,
            Integer pageFrom,
            Integer pageTo,
            String sectionPath,
            BoundingBox boundingBox
    ) {
        public Block {
            requireText("blockId", blockId);
            if (type == null) {
                throw new IllegalArgumentException("block type is required");
            }
            requireText("block text", text);
            if (headingLevel != null
                    && (headingLevel < 1 || headingLevel > 7)) {
                throw new IllegalArgumentException(
                        "headingLevel must be between 1 and 7"
                );
            }
            if (pageFrom != null && pageFrom <= 0) {
                throw new IllegalArgumentException("pageFrom must be positive");
            }
            if (pageTo != null && pageTo <= 0) {
                throw new IllegalArgumentException("pageTo must be positive");
            }
            if (pageFrom != null && pageTo != null && pageTo < pageFrom) {
                throw new IllegalArgumentException("pageTo must be >= pageFrom");
            }
            sectionPath = sectionPath == null ? "" : sectionPath.trim();
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
}
