package kz.alimbetov.akmai.knowledge.model;

public record SemanticUnit(
        String text,
        String sectionPath,
        SemanticUnitType type,
        boolean protectedAtom,
        StructuralRole structuralRole
) {
    public SemanticUnit(
            String text,
            String sectionPath,
            SemanticUnitType type,
            boolean protectedAtom
    ) {
        this(
                text,
                sectionPath,
                type,
                protectedAtom,
                type == SemanticUnitType.HEADING
                        ? StructuralRole.HEADING
                        : StructuralRole.PARAGRAPH
        );
    }
}
