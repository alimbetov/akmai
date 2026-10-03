package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import kz.alimbetov.akmai.knowledge.model.StructuralRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StructuralUnitExtractor {

    private static final Pattern MARKDOWN_HEADING =
            Pattern.compile("^(#{1,6})\\s+(.+)$");

    private static final Pattern NUMBERED_LINE =
            Pattern.compile("^\\d+(?:\\.\\d+)*[.)]?\\s+.+$");

    private IndustryProfileRegistry profileRegistry;

    @Autowired(required = false)
    void setProfileRegistry(
            IndustryProfileRegistry profileRegistry
    ) {
        this.profileRegistry = profileRegistry;
    }

    public List<SemanticUnit> extract(
            KnowledgeDocument document,
            String normalizedText
    ) {
        List<SemanticUnit> units = new ArrayList<>();
        Deque<Heading> hierarchy = new ArrayDeque<>();
        String currentSection = document.title();

        LanguageProfile language =
                LanguageProfiles.forCode(document.language());
        IndustryProfile industry = profileRegistry == null
                ? IndustryProfiles.defaultFor(document.domain())
                : profileRegistry.forDocument(document);

        if (document.domain() == KnowledgeDomain.LEGAL
                && document.title() != null
                && !document.title().isBlank()) {
            hierarchy.addLast(new Heading(1, document.title()));
        }

        for (String block : normalizedText.split("\\n\\s*\\n")) {
            String value = block.trim();
            if (value.isEmpty()) {
                continue;
            }

            Heading heading = heading(value, industry, language);
            if (heading != null && value.length() < 220) {
                while (!hierarchy.isEmpty()
                        && hierarchy.peekLast().level() >= heading.level()) {
                    hierarchy.removeLast();
                }
                hierarchy.addLast(heading);
                currentSection = hierarchy.stream()
                        .map(Heading::text)
                        .collect(Collectors.joining(" > "));
                units.add(new SemanticUnit(
                        value,
                        currentSection,
                        SemanticUnitType.HEADING,
                        true,
                        StructuralRole.HEADING
                ));
                continue;
            }

            for (String paragraph : MultilingualSentenceSplitter.split(
                    value,
                    language
            )) {
                String text = paragraph.trim();
                if (!text.isEmpty()) {
                    StructuralRole role =
                            NUMBERED_LINE.matcher(text).matches()
                                    ? StructuralRole.LIST_ITEM
                                    : StructuralRole.PARAGRAPH;
                    units.add(new SemanticUnit(
                            text,
                            currentSection,
                            SemanticUnitType.PARAGRAPH,
                            false,
                            role
                    ));
                }
            }
        }

        return units;
    }

    private Heading heading(
            String value,
            IndustryProfile industry,
            LanguageProfile language
    ) {
        Matcher markdown = MARKDOWN_HEADING.matcher(value);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), value);
        }

        return industry.matchHeading(value, language)
                .map(match -> new Heading(
                        match.level(),
                        match.text()
                ))
                .orElse(null);
    }

    private record Heading(
            int level,
            String text
    ) {
    }
}
