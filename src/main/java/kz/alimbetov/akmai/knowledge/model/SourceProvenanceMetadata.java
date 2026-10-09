package kz.alimbetov.akmai.knowledge.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;

/**
 * Compatibility bridge between typed source identity and the existing metadata
 * carried through KnowledgeDocument -> chunks -> projections/vector rows.
 */
public final class SourceProvenanceMetadata {

    public static final String SOURCE_TYPE = "sourceType";
    public static final String FILE_ID = "sourceFileId";
    public static final String SOURCE_VERSION = "sourceVersion";
    public static final String FILE_NAME = "sourceFileName";
    public static final String MEDIA_TYPE = "sourceMediaType";
    public static final String CONTENT_HASH = "sourceContentHash";
    public static final String CANONICAL_SCHEMA_VERSION = "canonicalSchemaVersion";
    public static final String CANONICAL_HASH = "canonicalHash";
    public static final String PARSER = "sourceParser";
    public static final String PARSER_VERSION = "sourceParserVersion";
    public static final String PARSED_AT = "sourceParsedAt";

    private SourceProvenanceMetadata() {
    }

    public static void putSource(
            Map<String, Object> target,
            CanonicalKnowledgeDocument document,
            String canonicalHash
    ) {
        if (target == null || document == null) {
            return;
        }
        var source = document.source();
        target.put(SOURCE_TYPE, source.type().name());
        target.put(FILE_ID, source.fileId());
        target.put(SOURCE_VERSION, source.sourceVersion());
        target.put(FILE_NAME, source.fileName());
        target.put(MEDIA_TYPE, source.mediaType());
        target.put(CONTENT_HASH, source.contentHash());
        target.put(CANONICAL_SCHEMA_VERSION, document.schemaVersion());
        if (canonicalHash != null && !canonicalHash.isBlank()) {
            target.put(CANONICAL_HASH, canonicalHash.trim());
        }
        if (document.processing() != null) {
            target.put(PARSER, document.processing().parser());
            target.put(PARSER_VERSION, document.processing().parserVersion());
            target.put(PARSED_AT, document.processing().parsedAt().toString());
        }
    }

    public static SourceProvenance fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        SourceProvenance result = new SourceProvenance(
                string(metadata.get(SOURCE_TYPE)),
                string(metadata.get(FILE_ID)),
                string(metadata.get(SOURCE_VERSION)),
                string(metadata.get(FILE_NAME)),
                string(metadata.get(MEDIA_TYPE)),
                string(metadata.get(CONTENT_HASH)),
                strings(metadata.get("blockIds")),
                integer(metadata.get("pageFrom")),
                integer(metadata.get("pageTo")),
                sectionPath(metadata.get("sectionPath"))
        );
        return result.present() ? result : null;
    }

    public static Map<String, Object> sourceSnapshot(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        copy(metadata, result, SOURCE_TYPE);
        copy(metadata, result, FILE_ID);
        copy(metadata, result, SOURCE_VERSION);
        copy(metadata, result, FILE_NAME);
        copy(metadata, result, MEDIA_TYPE);
        copy(metadata, result, CONTENT_HASH);
        copy(metadata, result, CANONICAL_SCHEMA_VERSION);
        copy(metadata, result, CANONICAL_HASH);
        copy(metadata, result, PARSER);
        copy(metadata, result, PARSER_VERSION);
        copy(metadata, result, PARSED_AT);
        return Map.copyOf(result);
    }

    private static void copy(
            Map<String, Object> source,
            Map<String, Object> target,
            String key
    ) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = string(item);
            if (text != null && !result.contains(text)) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> sectionPath(Object value) {
        if (value instanceof List<?> list) {
            return strings(list);
        }
        String path = string(value);
        if (path == null) {
            return List.of();
        }
        return java.util.Arrays.stream(path.split("\\s*>\\s*"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
    }
}
