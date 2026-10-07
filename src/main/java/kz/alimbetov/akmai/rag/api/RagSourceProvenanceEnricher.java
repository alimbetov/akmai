package kz.alimbetov.akmai.rag.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RagSourceProvenanceEnricher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RagSourceProvenanceEnricher.class);

    private final PublishedSearchProjectionReader projectionReader;

    public RagSourceProvenanceEnricher(
            PublishedSearchProjectionReader projectionReader
    ) {
        this.projectionReader = projectionReader;
    }

    public RagResponse enrich(
            RagResponse response,
            Set<Long> accessLevels
    ) {
        if (response == null
                || response.sources() == null
                || response.sources().isEmpty()
                || accessLevels == null
                || accessLevels.isEmpty()) {
            return response;
        }
        try {
            Map<Key, SearchProjection> projections = load(
                    response.sources(),
                    accessLevels
            );
            List<RagResponse.Source> sources = response.sources().stream()
                    .map(source -> enrich(source, projections.get(new Key(
                            source.documentId(),
                            source.chunkId()
                    ))))
                    .toList();
            return new RagResponse(
                    response.requestId(),
                    response.answer(),
                    sources
            );
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "rag_provenance event=enrichment_failed errorType={}",
                    exception.getClass().getSimpleName()
            );
            return response;
        }
    }

    private Map<Key, SearchProjection> load(
            List<RagResponse.Source> sources,
            Set<Long> accessLevels
    ) {
        Map<String, List<String>> byDocument = sources.stream()
                .filter(this::usable)
                .collect(Collectors.groupingBy(
                        RagResponse.Source::documentId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                RagResponse.Source::chunkId,
                                Collectors.collectingAndThen(
                                        Collectors.toList(),
                                        values -> values.stream().distinct().toList()
                                )
                        )
                ));
        Map<Key, SearchProjection> result = new HashMap<>();
        byDocument.forEach((documentId, chunkIds) ->
                projectionReader.findByDocumentAndChunkIds(
                        documentId,
                        chunkIds,
                        accessLevels
                ).forEach(projection -> result.put(
                        new Key(
                                projection.documentId(),
                                projection.chunkId()
                        ),
                        projection
                ))
        );
        return Map.copyOf(result);
    }

    private RagResponse.Source enrich(
            RagResponse.Source source,
            SearchProjection projection
    ) {
        if (source == null || projection == null) {
            return source;
        }
        List<RagResponse.CanonicalBlock> blocks = canonicalBlocks(
                projection.metadata()
        );
        return new RagResponse.Source(
                source.number(),
                source.documentId(),
                source.chunkId(),
                source.source(),
                source.language(),
                source.sectionPath(),
                source.page(),
                new RagResponse.Provenance(blocks)
        );
    }

    private List<RagResponse.CanonicalBlock> canonicalBlocks(
            Map<String, Object> metadata
    ) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        Object raw = metadata.get("canonicalBlocks");
        if (raw instanceof List<?> values) {
            List<RagResponse.CanonicalBlock> blocks = values.stream()
                    .map(this::canonicalBlock)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!blocks.isEmpty()) {
                return blocks;
            }
        }
        return legacyBlocks(metadata);
    }

    private RagResponse.CanonicalBlock canonicalBlock(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) {
            return null;
        }
        String blockId = text(value.get("blockId"));
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        return new RagResponse.CanonicalBlock(
                blockId,
                integer(value.get("pageFrom")),
                integer(value.get("pageTo")),
                text(value.get("sectionPath")),
                boundingBox(value.get("boundingBox"))
        );
    }

    private List<RagResponse.CanonicalBlock> legacyBlocks(
            Map<String, Object> metadata
    ) {
        Object raw = metadata.get("blockIds");
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        Integer pageFrom = integer(metadata.get("pageFrom"));
        Integer pageTo = integer(metadata.get("pageTo"));
        String sectionPath = text(metadata.get("sectionPath"));
        List<RagResponse.CanonicalBlock> result = new ArrayList<>();
        for (Object value : values) {
            String blockId = text(value);
            if (blockId == null || blockId.isBlank()) {
                continue;
            }
            result.add(new RagResponse.CanonicalBlock(
                    blockId,
                    pageFrom,
                    pageTo,
                    sectionPath,
                    null
            ));
        }
        return List.copyOf(result);
    }

    private RagResponse.BoundingBox boundingBox(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) {
            return null;
        }
        Double x = decimal(value.get("x"));
        Double y = decimal(value.get("y"));
        Double width = decimal(value.get("width"));
        Double height = decimal(value.get("height"));
        if (x == null
                || y == null
                || width == null
                || height == null
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(width)
                || !Double.isFinite(height)
                || x < 0
                || y < 0
                || width < 0
                || height < 0) {
            return null;
        }
        return new RagResponse.BoundingBox(x, y, width, height);
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private boolean usable(RagResponse.Source source) {
        return source != null
                && source.documentId() != null
                && !source.documentId().isBlank()
                && source.chunkId() != null
                && !source.chunkId().isBlank()
                && !"unknown".equalsIgnoreCase(source.chunkId());
    }

    private record Key(String documentId, String chunkId) {
    }
}
