package kz.alimbetov.akmai.knowledge.chunking.boundary;

import java.util.Set;

/** Conservative fallback profile for unknown or unsupported language tags. */
public final class GenericLanguageDelimiterProfile extends AbstractLanguageDelimiterProfile {

    private final String languageCode;
    private final Set<String> abbreviations;

    public GenericLanguageDelimiterProfile(String languageCode, Set<String> abbreviations) {
        this.languageCode = languageCode;
        this.abbreviations = Set.copyOf(abbreviations);
    }

    @Override
    public String languageCode() {
        return languageCode;
    }

    @Override
    protected Set<String> abbreviations() {
        return abbreviations;
    }
}
