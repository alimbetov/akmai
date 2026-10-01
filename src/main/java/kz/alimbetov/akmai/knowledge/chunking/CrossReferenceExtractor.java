package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CrossReferenceExtractor {

    private static final Pattern REFERENCE = Pattern.compile(
            "(?i)(?:стать(?:я|и|е|ю|ёй|ей)|пункт(?:а|е|у)?|article|section|clause|бап|тармақ)\\s+[0-9]+(?:\\.[0-9]+)*"
    );

    public List<String> extract(String text) {
        List<String> references = new ArrayList<>();
        Matcher matcher = REFERENCE.matcher(text);

        while (matcher.find()) {
            references.add(matcher.group().trim());
        }

        return references.stream().distinct().toList();
    }
}
