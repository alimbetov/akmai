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

        int maxCharacters = Math.max(1, (int) Math.floor(hardMaxTokens * 3.2));
        List<SemanticUnit> result = new ArrayList<>();
        String remaining = unit.text().trim();

        while (!remaining.isEmpty()) {
            if (tokenEstimator.estimate(remaining) <= hardMaxTokens) {
                result.add(copy(unit, remaining));
                break;
            }

            int candidateEnd = Math.min(maxCharacters, remaining.length());
            int splitAt = boundary(remaining, candidateEnd);
            String part = remaining.substring(0, splitAt).trim();
            if (part.isEmpty()) {
                splitAt = candidateEnd;
                part = remaining.substring(0, splitAt).trim();
            }

            result.add(copy(unit, part));
            remaining = remaining.substring(splitAt).trim();
        }

        return List.copyOf(result);
    }

    private int boundary(String text, int from) {
        for (int index = from; index > Math.max(0, from / 2); index--) {
            char value = text.charAt(index - 1);
            if (value == '\n' || value == '.' || value == ';' || value == '。'
                    || value == '!' || value == '?' || Character.isWhitespace(value)) {
                return index;
            }
        }
        return from;
    }

    private SemanticUnit copy(SemanticUnit source, String text) {
        return new SemanticUnit(
                text,
                source.sectionPath(),
                source.type(),
                source.protectedAtom()
        );
    }
}
