package kz.alimbetov.akmai.knowledge.model;

import java.util.List;

/** Stable retrieval-facing provenance. Storage credentials are intentionally absent. */
public record SourceProvenance(
        String sourceType,
        String fileId,
        String sourceVersion,
        String fileName,
        String mediaType,
        String contentHash,
        List<String> blockIds,
        Integer pageFrom,
        Integer pageTo,
        List<String> sectionPath
) {
    public SourceProvenance {
        sourceType = normalize(sourceType);
        fileId = normalize(fileId);
        sourceVersion = normalize(sourceVersion);
        fileName = normalize(fileName);
        mediaType = normalize(mediaType);
        contentHash = normalize(contentHash);
        blockIds = normalizeList(blockIds);
        sectionPath = normalizeList(sectionPath);
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

    public boolean present() {
        return fileId != null || contentHash != null || !blockIds.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}
