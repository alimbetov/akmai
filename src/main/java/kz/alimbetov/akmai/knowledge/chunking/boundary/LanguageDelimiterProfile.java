package kz.alimbetov.akmai.knowledge.chunking.boundary;

import java.util.List;

/**
 * Supplies language-specific boundary candidates without owning token-budget policy.
 */
public interface LanguageDelimiterProfile {

    String languageCode();

    List<BoundaryCandidate> candidates(String text, int minOffset, int maxOffset);
}
