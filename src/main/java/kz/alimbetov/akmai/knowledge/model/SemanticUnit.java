package kz.alimbetov.akmai.knowledge.model;

public record SemanticUnit(
        String text,
        String sectionPath,
        SemanticUnitType type,
        boolean protectedAtom,
        StructuralRole structuralRole,
        UnitProvenance provenance
) {
    public SemanticUnit(
            String text,
            String sectionPath,
            SemanticUnitType type,
            boolean protectedAtom,
            StructuralRole structuralRole
    ) {
        this(
                text,
                sectionPath,
                type,
                protectedAtom,
                structuralRole,
                null
        );
    }

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
                        : StructuralRole.PARAGRAPH,
                null
        );
    }
}
