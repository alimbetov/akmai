package kz.alimbetov.akmai.knowledge.model;

public record SemanticUnit(
        String text,
        String sectionPath,
        SemanticUnitType type,
        boolean protectedAtom
) {
}
