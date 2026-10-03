package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;

final class GenericIndustryProfile implements IndustryProfile {

    private static final int PATTERN_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private final IndustryCode code;
    private final Map<String, List<HeadingPattern>> headings;
    private final Map<String, Map<SemanticUnitType, List<Pattern>>>
            semanticTypes;

    GenericIndustryProfile(IndustryDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "industry definition must not be null"
            );
        }
        this.code = new IndustryCode(
                definition.id(),
                definition.domain(),
                definition.names()
        );
        this.headings = compileHeadings(definition.headings());
        this.semanticTypes = compileSemanticTypes(
                definition.semanticTypes()
        );
    }

    @Override
    public IndustryCode code() {
        return code;
    }

    @Override
    public Optional<HeadingMatch> matchHeading(
            String line,
            LanguageProfile language
    ) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        for (HeadingPattern candidate : rulesFor(
                headings,
                language
        )) {
            if (candidate.pattern().matcher(line).matches()) {
                return Optional.of(
                        new HeadingMatch(candidate.level(), line)
                );
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<SemanticUnitType> classifyType(
            String text,
            LanguageProfile language
    ) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        for (Map<SemanticUnitType, List<Pattern>> byType
                : mapsFor(semanticTypes, language)) {
            for (SemanticUnitType type : SemanticUnitType.values()) {
                for (Pattern pattern : byType.getOrDefault(
                        type,
                        List.of()
                )) {
                    if (pattern.matcher(text).find()) {
                        return Optional.of(type);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, List<HeadingPattern>> compileHeadings(
            Map<String, List<IndustryDefinition.HeadingRule>> raw
    ) {
        Map<String, List<HeadingPattern>> result =
                new LinkedHashMap<>();
        raw.forEach((language, rules) -> {
            validateLanguageKey(language);
            result.put(
                    language,
                    rules.stream()
                            .map(rule -> new HeadingPattern(
                                    rule.level(),
                                    Pattern.compile(
                                            rule.pattern(),
                                            PATTERN_FLAGS
                                    )
                            ))
                            .toList()
            );
        });
        return Map.copyOf(result);
    }

    private Map<String, Map<SemanticUnitType, List<Pattern>>>
            compileSemanticTypes(
                    Map<String, Map<String, List<String>>> raw
            ) {
        Map<String, Map<SemanticUnitType, List<Pattern>>> result =
                new LinkedHashMap<>();

        raw.forEach((language, byType) -> {
            validateLanguageKey(language);
            EnumMap<SemanticUnitType, List<Pattern>> compiled =
                    new EnumMap<>(SemanticUnitType.class);
            byType.forEach((type, patterns) -> compiled.put(
                    SemanticUnitType.valueOf(type),
                    patterns.stream()
                            .map(value -> Pattern.compile(
                                    value,
                                    PATTERN_FLAGS
                            ))
                            .toList()
            ));
            result.put(language, Map.copyOf(compiled));
        });
        return Map.copyOf(result);
    }

    private void validateLanguageKey(String language) {
        if ("*".equals(language)) {
            return;
        }
        KnowledgeLanguage parsed = KnowledgeLanguage.parse(language);
        if (!parsed.code().equals(language)) {
            throw new IllegalArgumentException(
                    "industry profile language keys must be canonical: "
                            + language
            );
        }
    }

    private <T> List<T> rulesFor(
            Map<String, List<T>> source,
            LanguageProfile language
    ) {
        List<T> result = new ArrayList<>();
        result.addAll(source.getOrDefault(language.code(), List.of()));
        result.addAll(source.getOrDefault("*", List.of()));
        return result;
    }

    private <T> List<Map<T, List<Pattern>>> mapsFor(
            Map<String, Map<T, List<Pattern>>> source,
            LanguageProfile language
    ) {
        List<Map<T, List<Pattern>>> result = new ArrayList<>();
        Map<T, List<Pattern>> local = source.get(language.code());
        if (local != null) {
            result.add(local);
        }
        Map<T, List<Pattern>> global = source.get("*");
        if (global != null) {
            result.add(global);
        }
        return result;
    }

    private record HeadingPattern(
            int level,
            Pattern pattern
    ) {
    }
}
