package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.springframework.stereotype.Component;

@Component
public class StructuralUnitExtractor {

    private static final Pattern HEADING = Pattern.compile(
            "^(#{1,6}\\s+.+|(?:Статья|Глава|Раздел|Article|Chapter|Section)\\s+.+|\\d+(?:\\.\\d+)*[.)]?\\s+.+)$",
            Pattern.CASE_INSENSITIVE
    );

    public List<SemanticUnit> extract(KnowledgeDocument document, String normalizedText) {
        List<SemanticUnit> units = new ArrayList<>();
        String currentSection = document.title();

        for (String block : normalizedText.split("\\n\\s*\\n")) {
            String value = block.trim();
            if (value.isEmpty()) {
                continue;
            }

            if (HEADING.matcher(value).matches() && value.length() < 220) {
                currentSection = value;
                units.add(new SemanticUnit(value, currentSection, SemanticUnitType.HEADING, true));
                continue;
            }

            for (String paragraph : value.split("(?<=[.!?。！？])\\s+(?=[A-ZА-ЯӘІҢҒҮҰҚӨҺ0-9])")) {
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
}
