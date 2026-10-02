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
            Pattern.compile("(?<=[!?;])\\s+|(?<=[。！？；])|(?<=\\.)\\s+");

    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(ст|п|art|no)\\.\\s*(?=\\d)"
    );

    private static final Pattern COORDINATING_BOUNDARY = Pattern.compile(
            "(?i)\\s+(?:and|as well as|и|а также|және|сондай-ақ)\\s+|(?:以及|并且)"
    );

    private static final List<String> INTENT_MARKERS = List.of(
            "what ", "which ", "how ", "when ", "where ", "why ", "who ",
            "какой ", "какая ", "какие ", "как ", "когда ", "где ", "почему ", "кто ",
            "қандай ", "қалай ", "қашан ", "қайда ", "неге ", "кім ",
            "是什么", "是多少", "如何", "什么时候", "哪里", "为什么", "谁"
    );

    private static final String DOT_SENTINEL = "\uE000";

    private final TextNormalizer normalizer;

    public QueryDecomposer(TextNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public List<String> decompose(String question) {
        return decomposeDetailed(question).units();
    }

    public QueryDecompositionResult decomposeDetailed(String question) {
        String normalized = normalizer.normalize(question);
        if (normalized.isBlank()) {
            return new QueryDecompositionResult(List.of(), 0);
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);

        String protectedText = protectAbbreviations(normalized);
        for (String primary : SENTENCE_BOUNDARY.split(protectedText)) {
            String segment = normalizer.normalize(restoreAbbreviations(primary));
            if (segment.isBlank()) {
                continue;
            }
            candidates.add(segment);
            List<String> coordinated = coordinatedClauses(segment);
            if (coordinated.size() > 1) {
                candidates.addAll(coordinated);
            }
        }

        List<String> all = List.copyOf(candidates);
        int allowed = Math.min(MAX_SEGMENTS, all.size());
        int overflow = Math.max(0, all.size() - allowed);
        return new QueryDecompositionResult(
                List.copyOf(all.subList(0, allowed)),
                overflow
        );
    }

    private String protectAbbreviations(String value) {
        return ABBREVIATION.matcher(value)
                .replaceAll(match -> match.group(1) + DOT_SENTINEL + " ");
    }

    private String restoreAbbreviations(String value) {
        return value.replace(DOT_SENTINEL, ".");
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
}
