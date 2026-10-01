package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.springframework.stereotype.Component;

@Component
public class StructuralUnitExtractor {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LEGAL_HEADING = Pattern.compile(
            "^(LAW|ЗАКОН|ЗАҢ|法律|PART|ЧАСТЬ|БӨЛІМ|编|CHAPTER|ГЛАВА|ТАРАУ|章|SECTION|РАЗДЕЛ|БӨЛІК|节|ARTICLE|СТАТЬЯ|БАП|条|PARAGRAPH|ПАРАГРАФ|ТАРМАҚ|款|SUBPARAGRAPH|ПОДПАРАГРАФ|ТАРМАҚША|项)\\b.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+(?:\\.\\d+)*[.)]?\\s+.+$");

    public List<SemanticUnit> extract(KnowledgeDocument document, String normalizedText) {
        List<SemanticUnit> units = new ArrayList<>();
        Deque<Heading> hierarchy = new ArrayDeque<>();
        String currentSection = document.title();

        for (String block : normalizedText.split("\\n\\s*\\n")) {
            String value = block.trim();
            if (value.isEmpty()) {
                continue;
            }

            Heading heading = heading(value);
            if (heading != null && value.length() < 220) {
                while (!hierarchy.isEmpty() && hierarchy.peekLast().level() >= heading.level()) {
                    hierarchy.removeLast();
                }
                hierarchy.addLast(heading);
                currentSection = hierarchy.stream()
                        .map(Heading::text)
                        .collect(Collectors.joining(" > "));
                units.add(new SemanticUnit(value, currentSection, SemanticUnitType.HEADING, true));
                continue;
            }

            for (String paragraph : value.split("(?<=[.!?。！？])\\s+(?=[A-ZА-ЯӘІҢҒҮҰҚӨҺ0-9一-龥])")) {
                String text = paragraph.trim();
                if (!text.isEmpty()) {
                    units.add(new SemanticUnit(
                            text,
                            currentSection,
                            SemanticUnitType.PARAGRAPH,
                            false
                    ));
                }
            }
        }

        return units;
    }

    private Heading heading(String value) {
        Matcher markdown = MARKDOWN_HEADING.matcher(value);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), value);
        }

        Matcher legal = LEGAL_HEADING.matcher(value);
        if (legal.matches()) {
            return new Heading(legalLevel(value), value);
        }

        if (NUMBERED_HEADING.matcher(value).matches()) {
            return new Heading(numberedLevel(value), value);
        }

        return null;
    }

    private int legalLevel(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (startsWithAny(upper, "LAW", "ЗАКОН", "ЗАҢ", "法律")) return 1;
        if (startsWithAny(upper, "PART", "ЧАСТЬ", "БӨЛІМ", "编")) return 2;
        if (startsWithAny(upper, "CHAPTER", "ГЛАВА", "ТАРАУ", "章")) return 3;
        if (startsWithAny(upper, "SECTION", "РАЗДЕЛ", "БӨЛІК", "节")) return 4;
        if (startsWithAny(upper, "ARTICLE", "СТАТЬЯ", "БАП", "条")) return 5;
        if (startsWithAny(upper, "PARAGRAPH", "ПАРАГРАФ", "ТАРМАҚ", "款")) return 6;
        return 7;
    }

    private int numberedLevel(String value) {
        String token = value.split("\\s+", 2)[0].replaceAll("[.)]+$", "");
        return Math.min(7, 5 + Math.max(0, token.split("\\.").length - 1));
    }

    private boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private record Heading(int level, String text) {}
}
