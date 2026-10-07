package kz.alimbetov.akmai.knowledge.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public record UnitProvenance(
        List<String> blockIds,
        Integer pageFrom,
        Integer pageTo,
        String sectionPath,
        List<BlockRef> blockRefs
) {
    public UnitProvenance(
            List<String> blockIds,
            Integer pageFrom,
            Integer pageTo,
            String sectionPath
    ) {
        this(
                blockIds,
                pageFrom,
                pageTo,
                sectionPath,
                synthesizeBlockRefs(blockIds, pageFrom, pageTo, sectionPath)
        );
    }

    public UnitProvenance {
        blockIds = normalizeBlockIds(blockIds);
        blockRefs = normalizeBlockRefs(blockRefs);
        if (!blockRefs.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(blockIds);
            blockRefs.stream().map(BlockRef::blockId).forEach(merged::add);
            blockIds = List.copyOf(merged);
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

    public static UnitProvenance merge(UnitProvenance left, UnitProvenance right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        LinkedHashSet<String> blocks = new LinkedHashSet<>(left.blockIds());
        blocks.addAll(right.blockIds());
        LinkedHashMap<String, BlockRef> refs = new LinkedHashMap<>();
        left.blockRefs().forEach(ref -> refs.putIfAbsent(ref.blockId(), ref));
        right.blockRefs().forEach(ref -> refs.putIfAbsent(ref.blockId(), ref));
        Integer from = min(left.pageFrom(), right.pageFrom());
        Integer to = max(left.pageTo(), right.pageTo());
        String section = !right.sectionPath().isBlank()
                ? right.sectionPath()
                : left.sectionPath();
        return new UnitProvenance(
                List.copyOf(blocks),
                from,
                to,
                section,
                List.copyOf(refs.values())
        );
    }

    private static List<String> normalizeBlockIds(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
    }

    private static List<BlockRef> normalizeBlockRefs(List<BlockRef> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, BlockRef> unique = new LinkedHashMap<>();
        values.stream()
                .filter(java.util.Objects::nonNull)
                .forEach(value -> unique.putIfAbsent(value.blockId(), value));
        return List.copyOf(unique.values());
    }

    private static List<BlockRef> synthesizeBlockRefs(
            List<String> blockIds,
            Integer pageFrom,
            Integer pageTo,
            String sectionPath
    ) {
        return normalizeBlockIds(blockIds).stream()
                .map(blockId -> new BlockRef(
                        blockId,
                        pageFrom,
                        pageTo,
                        sectionPath,
                        null
                ))
                .toList();
    }

    private static Integer min(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.min(left, right);
    }

    private static Integer max(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    public record BlockRef(
            String blockId,
            Integer pageFrom,
            Integer pageTo,
            String sectionPath,
            BoundingBox boundingBox
    ) {
        public BlockRef {
            if (blockId == null || blockId.isBlank()) {
                throw new IllegalArgumentException("blockId must not be blank");
            }
            blockId = blockId.trim();
            if (pageFrom != null && pageFrom <= 0) {
                throw new IllegalArgumentException("block pageFrom must be positive");
            }
            if (pageTo != null && pageTo <= 0) {
                throw new IllegalArgumentException("block pageTo must be positive");
            }
            if (pageFrom != null && pageTo != null && pageTo < pageFrom) {
                throw new IllegalArgumentException("block pageTo must be >= pageFrom");
            }
            sectionPath = sectionPath == null ? "" : sectionPath.trim();
        }
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
