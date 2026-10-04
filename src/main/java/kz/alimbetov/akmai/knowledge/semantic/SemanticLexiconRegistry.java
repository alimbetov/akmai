package kz.alimbetov.akmai.knowledge.semantic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SemanticLexiconRegistry {

    public static final int MIN_TERMS_PER_DOMAIN_LANGUAGE = 1000;

    private final SemanticDomainCatalog catalog;
    private final Map<Key, List<SemanticLexeme>> cache =
            new ConcurrentHashMap<>();

    public SemanticLexiconRegistry(SemanticDomainCatalog catalog) {
        this.catalog = catalog;
    }

    public List<SemanticLexeme> lexemes(
            String domainId,
            String language
    ) {
        Key key = new Key(domainId, language);
        return cache.computeIfAbsent(key, this::build);
    }

    public int entryCount(String domainId, String language) {
        return lexemes(domainId, language).size();
    }

    public List<String> anchors(String domainId, String language) {
        return catalog.require(domainId)
                .anchors()
                .getOrDefault(language, List.of());
    }

    private List<SemanticLexeme> build(Key key) {
        SemanticDomainDefinition domain = catalog.require(key.domainId());
        List<String> anchors = domain.anchors().get(key.language());
        if (anchors == null || anchors.isEmpty()) {
            throw new IllegalArgumentException(
                    "No semantic anchors for "
                            + key.domainId() + "/" + key.language()
            );
        }

        SemanticExpansionVocabulary.Vocabulary vocabulary =
                SemanticExpansionVocabulary.require(key.language());
        LinkedHashMap<String, SemanticLexeme> unique =
                new LinkedHashMap<>();

        for (String anchor : anchors) {
            put(unique, new SemanticLexeme(
                    domain.id(),
                    key.language(),
                    normalize(anchor),
                    1.0,
                    true
            ));
            for (String qualifier : vocabulary.qualifiers()) {
                for (String aspect : vocabulary.aspects()) {
                    String surface = String.join(
                            " ",
                            normalize(qualifier),
                            normalize(anchor),
                            normalize(aspect)
                    );
                    put(unique, new SemanticLexeme(
                            domain.id(),
                            key.language(),
                            surface,
                            0.35,
                            false
                    ));
                }
            }
        }

        List<SemanticLexeme> result =
                List.copyOf(new ArrayList<>(unique.values()));
        if (result.size() <= MIN_TERMS_PER_DOMAIN_LANGUAGE) {
            throw new IllegalStateException(
                    "Semantic lexicon coverage below required threshold for "
                            + key.domainId()
                            + "/"
                            + key.language()
                            + ": "
                            + result.size()
            );
        }
        return result;
    }

    private void put(
            Map<String, SemanticLexeme> target,
            SemanticLexeme lexeme
    ) {
        target.putIfAbsent(lexeme.surface(), lexeme);
    }

    static String normalize(String value) {
        return Normalizer.normalize(
                        value == null ? "" : value,
                        Normalizer.Form.NFC
                )
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private record Key(String domainId, String language) {
    }
}
