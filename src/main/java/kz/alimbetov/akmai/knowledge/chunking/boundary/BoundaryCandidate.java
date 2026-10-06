package kz.alimbetov.akmai.knowledge.chunking.boundary;

/**
 * Candidate split position produced by a language-aware delimiter profile.
 * Offset is a UTF-16 String index suitable for String.substring().
 */
public record BoundaryCandidate(
        int offset,
        BoundaryStrength strength,
        int languageBonus,
        int safetyPenalty,
        String reason) {

    public int score() {
        return strength.score() + languageBonus - safetyPenalty;
    }
}
