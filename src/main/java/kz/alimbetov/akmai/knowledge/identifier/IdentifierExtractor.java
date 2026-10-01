package kz.alimbetov.akmai.knowledge.identifier;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IdentifierExtractor {

    private final List<IdentifierParser> parsers;

    public IdentifierExtractor(List<IdentifierParser> parsers) {
        this.parsers = parsers;
    }

    public List<DetectedIdentifier> extract(String text) {
        return parsers.stream()
                .flatMap(parser -> parser.extract(text).stream())
                .filter(value -> !value.normalizedValue().isBlank())
                .distinct()
                .toList();
    }
}
