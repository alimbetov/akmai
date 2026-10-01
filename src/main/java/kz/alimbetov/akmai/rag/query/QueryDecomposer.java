package kz.alimbetov.akmai.rag.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import org.springframework.stereotype.Component;

@Component
public class QueryDecomposer {

    static final int MAX_SEGMENTS = 8;

    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?;。！？；])\\s+");

    private static final Pattern COORDINATING_BOUNDARY = Pattern.compile(
            "(?i)\\s+(?:and|as well as|и|а также|және|сондай-ақ)\\s+|(?:以及|并且)"
    );

    private static final List<String> INTENT_MARKERS = List.of(
            "what ", "which ", "how ", "when ", "where ", "why ", "who ",
            "какой ", "какая ", "какие ", "как ", "когда ", "где ", "почему ", "кто ",
            "қандай ", "қалай ", "қашан ", "қайда ", "неге ", "кім ",
            "是什么", "是多少", "如何", "什么时候", "哪里", "为什么", "谁"
    );

    private final TextNormalizer normalizer;

    public QueryDecomposer(TextNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public List<String> decompose(String question) {
        String normalized = normalizer.normalize(question);
        if (normalized.isBlank()) {
            return List.of();
        }

        Set<String> result = new LinkedHashSet<>();
        for (String primary : SENTENCE_BOUNDARY.split(normalized)) {
            String segment = normalizer.normalize(primary);
            if (segment.isBlank()) {
                continue;
            }

            List<String> coordinated = coordinatedClauses(segment);
            if (coordinated.size() > 1) {
                addBounded(result, segment);
                for (String clause : coordinated) {
                    addBounded(result, clause);
                }
            } else {
                addBounded(result, segment);
            }

            if (result.size() >= MAX_SEGMENTS) {
                break;
            }
        }

        return List.copyOf(result);
    }

    private List<String> coordinatedClauses(String segment) {
        String[] parts = COORDINATING_BOUNDARY.split(segment);
        if (parts.length < 2) {
            return List.of(segment);
        }

        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String normalized = normalizer.normalize(part);
            if (normalized.isBlank() || !hasIntentMarker(normalized)) {
                return List.of(segment);
            }
            clauses.add(normalized);
        }
        return List.copyOf(clauses);
    }

    private boolean hasIntentMarker(String value) {
        String lower = value.toLowerCase(Locale.ROOT).stripLeading();
        for (String marker : INTENT_MARKERS) {
            if (lower.startsWith(marker) || lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void addBounded(Set<String> result, String value) {
        if (result.size() < MAX_SEGMENTS) {
            result.add(normalizer.normalize(value));
        }
    }
}
