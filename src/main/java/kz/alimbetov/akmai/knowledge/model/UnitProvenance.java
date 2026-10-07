package kz.alimbetov.akmai.knowledge.model;

import java.util.LinkedHashSet;
import java.util.List;

public record UnitProvenance(
        List<String> blockIds,
        Integer pageFrom,
        Integer pageTo,
        String sectionPath
) {
    public UnitProvenance {
        blockIds = blockIds == null
                ? List.of()
                : blockIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
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
        Integer from = min(left.pageFrom(), right.pageFrom());
        Integer to = max(left.pageTo(), right.pageTo());
        String section = !right.sectionPath().isBlank()
                ? right.sectionPath()
                : left.sectionPath();
        return new UnitProvenance(List.copyOf(blocks), from, to, section);
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
}
