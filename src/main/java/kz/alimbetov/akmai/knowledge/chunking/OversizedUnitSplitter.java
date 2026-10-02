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
        if (tokenEstimator.estimate(unit.text()) <= hardMaxTokens) {
            return List.of(unit);
        }

        int maxCodePoints = Math.max(1, (int) Math.floor(hardMaxTokens * 3.2));
        List<SemanticUnit> result = new ArrayList<>();
        String remaining = unit.text().trim();

        while (!remaining.isEmpty()) {
            if (tokenEstimator.estimate(remaining) <= hardMaxTokens) {
                result.add(copy(unit, remaining));
                break;
            }

            int codePointCount = remaining.codePointCount(0, remaining.length());
            int requestedCodePoints = Math.min(maxCodePoints, codePointCount);
            int candidateEnd = remaining.offsetByCodePoints(0, requestedCodePoints);
            int splitAt = boundary(remaining, candidateEnd);
            splitAt = safeBoundary(remaining, splitAt);

            String part = remaining.substring(0, splitAt).trim();
            if (part.isEmpty()) {
                splitAt = safeBoundary(remaining, candidateEnd);
                part = remaining.substring(0, splitAt).trim();
            }
            if (part.isEmpty()) {
                int oneCodePoint = remaining.offsetByCodePoints(0, 1);
                splitAt = oneCodePoint;
                part = remaining.substring(0, splitAt);
            }

            // The character estimate is conservative but the hard invariant is
            // checked against the configured estimator.
            while (tokenEstimator.estimate(part) > hardMaxTokens
                    && part.codePointCount(0, part.length()) > 1) {
                int cp = part.codePointCount(0, part.length());
                int shortened = part.offsetByCodePoints(0, cp - 1);
                part = part.substring(0, shortened).trim();
                splitAt = shortened;
            }

            result.add(copy(unit, part));
            remaining = remaining.substring(splitAt).trim();
        }

        return List.copyOf(result);
    }

    private int boundary(String text, int from) {
        int index = safeBoundary(text, from);
        int minimum = text.offsetByCodePoints(
                0,
                Math.max(0, text.codePointCount(0, index) / 2)
        );
        while (index > minimum) {
            int previous = text.offsetByCodePoints(index, -1);
            int codePoint = text.codePointAt(previous);
            if (codePoint == '\n'
                    || codePoint == '.'
                    || codePoint == ';'
                    || codePoint == '。'
                    || codePoint == '！'
                    || codePoint == '？'
                    || Character.isWhitespace(codePoint)) {
                return index;
            }
            index = previous;
        }
        return safeBoundary(text, from);
    }

    private int safeBoundary(String text, int index) {
        int bounded = Math.max(0, Math.min(index, text.length()));
        if (bounded > 0
                && bounded < text.length()
                && Character.isHighSurrogate(text.charAt(bounded - 1))
                && Character.isLowSurrogate(text.charAt(bounded))) {
            return bounded - 1;
        }
        return bounded;
    }

    private SemanticUnit copy(SemanticUnit source, String text) {
        return new SemanticUnit(
                text,
                source.sectionPath(),
                source.type(),
                source.protectedAtom(),
                source.structuralRole()
        );
    }
}
