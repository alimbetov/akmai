package kz.alimbetov.akmai.knowledge.chunking.boundary;

/**
 * Relative semantic strength of a candidate chunk boundary.
 * Higher values are preferred when several boundaries fit the token budget.
 */
public enum BoundaryStrength {
    HARD_FALLBACK(0),
    WHITESPACE(10),
    COMMA(20),
    CLAUSE(35),
    SENTENCE(60),
    LIST_ITEM(75),
    PARAGRAPH(90),
    SECTION(100);

    private final int score;

    BoundaryStrength(int score) {
        this.score = score;
    }

    public int score() {
        return score;
    }
}
