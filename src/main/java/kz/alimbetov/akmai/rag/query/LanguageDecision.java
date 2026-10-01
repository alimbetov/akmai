package kz.alimbetov.akmai.rag.query;

import java.util.List;

public record LanguageDecision(
        String primary,
        List<String> candidates,
        double confidence
) {
    public LanguageDecision {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }
}
