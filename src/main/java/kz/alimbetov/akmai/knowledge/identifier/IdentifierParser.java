package kz.alimbetov.akmai.knowledge.identifier;

import java.util.List;

public interface IdentifierParser {

    IdentifierType type();

    List<DetectedIdentifier> extract(String text);
}
