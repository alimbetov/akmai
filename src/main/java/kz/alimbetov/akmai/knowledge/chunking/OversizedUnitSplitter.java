package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import org.springframework.stereotype.Component;

@Component
public class OversizedUnitSplitter {

    private final TokenEstimator tokenEstimator;

    public OversizedUnitSplitter(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    public List<SemanticUnit> split(SemanticUnit unit, int hardMaxTokens) {
        int preferredMinTokens = Math.max(1, hardMaxTokens * 5 / 6);
        return split(unit, preferredMinTokens, hardMaxTokens, null);
    }

    public List<SemanticUnit> split(
            SemanticUnit unit,
            int preferredMinTokens,
            int hardMaxTokens,
            String language
    ) {
        if (hardMaxTokens <= 0) {
            throw new IllegalArgumentException("hardMaxTokens must be positive");
        }
        int minimumTokens = Math.max(
                1,
                Math.min(preferredMinTokens, hardMaxTokens)
        );
        if (tokenEstimator.estimate(unit.text()) <= hardMaxTokens) {
            return List.of(unit);
        }

        List<SemanticUnit> result = new ArrayList<>();
        String remaining = unit.text().trim();

        while (!remaining.isEmpty()) {
            if (tokenEstimator.estimate(remaining) <= hardMaxTokens) {
                result.add(copy(unit, remaining));
                break;
            }

            int maximumEnd = largestPrefixWithinBudget(
                    remaining,
                    hardMaxTokens
            );
            if (maximumEnd <= 0) {
                throw new IllegalStateException(
                        "A single code point exceeds the configured token budget"
                );
            }
            int minimumEnd = firstPrefixAtLeast(
                    remaining,
                    minimumTokens
            );
            if (minimumEnd <= 0 || minimumEnd > maximumEnd) {
                minimumEnd = maximumEnd;
            }

            int splitAt = ChunkBoundarySelector.bestBoundary(
                    remaining,
                    minimumEnd,
                    maximumEnd,
                    language
            );
            String part = remaining.substring(0, splitAt).trim();
            if (part.isEmpty()) {
                splitAt = maximumEnd;
                part = remaining.substring(0, splitAt).trim();
            }
            if (part.isEmpty()) {
                throw new IllegalStateException(
                        "Unable to produce a non-empty chunk within token budget"
                );
            }
            if (tokenEstimator.estimate(part) > hardMaxTokens) {
                throw new IllegalStateException(
                        "Boundary selector exceeded configured token budget"
                );
            }

            result.add(copy(unit, part));
            remaining = remaining.substring(splitAt).trim();
        }

        return List.copyOf(result);
    }

    private int largestPrefixWithinBudget(String text, int tokenBudget) {
        int codePoints = text.codePointCount(0, text.length());
        int low = 1;
        int high = codePoints;
        int best = 0;

        while (low <= high) {
            int middle = (low + high) >>> 1;
            int end = text.offsetByCodePoints(0, middle);
            int tokens = tokenEstimator.estimate(text.substring(0, end));
            if (tokens <= tokenBudget) {
                best = end;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private int firstPrefixAtLeast(String text, int tokenFloor) {
        int codePoints = text.codePointCount(0, text.length());
        int low = 1;
        int high = codePoints;
        int best = text.length();

        while (low <= high) {
            int middle = (low + high) >>> 1;
            int end = text.offsetByCodePoints(0, middle);
            int tokens = tokenEstimator.estimate(text.substring(0, end));
            if (tokens >= tokenFloor) {
                best = end;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return best;
    }

    private SemanticUnit copy(SemanticUnit source, String text) {
        return new SemanticUnit(
                text,
                source.sectionPath(),
                source.type(),
                source.protectedAtom(),
                source.structuralRole(),
                source.provenance()
        );
    }
}
