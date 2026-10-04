package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public interface SemanticMorphologyNormalizer {

    String language();

    List<String> normalizeTokens(String text);

    List<String> lemmaTokens(String text);

    List<String> stemTokens(String text);

    default String lemmaPhrase(String text) {
        return String.join(" ", lemmaTokens(text));
    }
}
