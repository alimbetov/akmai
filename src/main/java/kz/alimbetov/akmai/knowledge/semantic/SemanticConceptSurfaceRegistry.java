package kz.alimbetov.akmai.knowledge.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SemanticConceptSurfaceRegistry {

    private static final List<String> MULTILINGUAL_RESOURCES = List.of(
            "semantic/concept-surfaces-ru-v1.yaml",
            "semantic/concept-surfaces-kk-v1.yaml",
            "semantic/concept-surfaces-zh-v1.yaml",
            "semantic/concept-surfaces-de-v1.yaml",
            "semantic/concept-surfaces-fr-v1.yaml",
            "semantic/concept-surfaces-es-v1.yaml",
            "semantic/concept-surfaces-pt-v1.yaml",
            "semantic/concept-surfaces-it-v1.yaml",
            "semantic/concept-surfaces-tr-v1.yaml",
            "semantic/concept-surfaces-el-v1.yaml"
    );
    private static final List<String> ALIAS_RESOURCES = List.of(
            "semantic/concept-aliases-core-v1.yaml",
            "semantic/concept-aliases-global-v1.yaml"
    );

    private final EnglishSemanticConceptCatalog catalog;
    private final Map<String, List<SemanticConceptSurface>> byLanguage;
    private final Map<String, String> versionByLanguage;

    public SemanticConceptSurfaceRegistry(
            EnglishSemanticConceptCatalog catalog,
            EnglishSemanticMorphologyNormalizer normalizer
    ) {
        this(
                catalog,
                new SemanticMorphologyRegistry(List.of(normalizer)),
                List.of(),
                null
        );
    }

    @Autowired
    public SemanticConceptSurfaceRegistry(
            EnglishSemanticConceptCatalog catalog,
            SemanticMorphologyRegistry morphologyRegistry
    ) {
        this(
                catalog,
                morphologyRegistry,
                loadMultilingualDefinitions(),
                loadAliasDefinition()
        );
    }

    SemanticConceptSurfaceRegistry(
            EnglishSemanticConceptCatalog catalog,
            SemanticMorphologyRegistry morphologyRegistry,
            List<MultilingualConceptSurfaceDefinition> definitions
    ) {
        this(catalog, morphologyRegistry, definitions, null);
    }

    SemanticConceptSurfaceRegistry(
            EnglishSemanticConceptCatalog catalog,
            SemanticMorphologyRegistry morphologyRegistry,
            List<MultilingualConceptSurfaceDefinition> definitions,
            SemanticConceptAliasDefinition aliasDefinition
    ) {
        this.catalog = catalog;

        LinkedHashMap<String, List<SemanticConceptSurface>> surfaces =
                new LinkedHashMap<>();
        LinkedHashMap<String, String> versions =
                new LinkedHashMap<>();

        SemanticMorphologyNormalizer english =
                morphologyRegistry.require("en");
        surfaces.put(
                "en",
                catalog.concepts().stream()
                        .map(concept ->
                                surface(
                                        concept,
                                        "en",
                                        concept.preferredPhrase(),
                                        List.of(),
                                        english
                                )
                        )
                        .toList()
        );
        versions.put("en", catalog.version());

        for (MultilingualConceptSurfaceDefinition definition :
                definitions == null ? List.<MultilingualConceptSurfaceDefinition>of()
                        : definitions) {
            if (definition == null
                    || definition.version() == null
                    || definition.version().isBlank()) {
                throw new IllegalArgumentException(
                        "Semantic surface version is required"
                );
            }

            for (var languagePack : definition.languages()) {
                String language = languagePack.language();
                if (language == null || language.isBlank()) {
                    throw new IllegalArgumentException(
                            "Semantic surface language is required"
                    );
                }
                if (surfaces.containsKey(language)) {
                    throw new IllegalArgumentException(
                            "Duplicate semantic surface language: "
                                    + language
                    );
                }

                SemanticMorphologyNormalizer normalizer =
                        morphologyRegistry.require(language);
                LinkedHashMap<String, SemanticConceptSurface> unique =
                        new LinkedHashMap<>();

                for (var definitionSurface :
                        languagePack.surfaces()) {
                    SemanticConcept concept =
                            catalog.require(
                                    definitionSurface.conceptId()
                            );
                    SemanticConceptSurface surface = surface(
                            concept,
                            language,
                            definitionSurface.preferredPhrase(),
                            definitionSurface.aliases(),
                            normalizer
                    );
                    if (unique.putIfAbsent(
                            surface.conceptId(),
                            surface
                    ) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate semantic surface for "
                                        + language
                                        + "/"
                                        + surface.conceptId()
                        );
                    }
                }

                if (unique.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Semantic surface pack is empty for "
                                    + language
                    );
                }

                surfaces.put(
                        language,
                        List.copyOf(unique.values())
                );
                versions.put(language, definition.version());
            }
        }

        applyAliases(
                surfaces,
                aliasDefinition,
                morphologyRegistry
        );

        this.byLanguage = Map.copyOf(surfaces);
        this.versionByLanguage = Map.copyOf(versions);
    }

    public String version(String language) {
        return versionByLanguage.get(language);
    }

    public List<SemanticConceptSurface> surfaces(String language) {
        return byLanguage.getOrDefault(language, List.of());
    }

    public boolean supports(String language) {
        return byLanguage.containsKey(language);
    }

    private void applyAliases(
            LinkedHashMap<String, List<SemanticConceptSurface>> surfaces,
            SemanticConceptAliasDefinition definition,
            SemanticMorphologyRegistry morphologyRegistry
    ) {
        if (definition == null) {
            return;
        }
        if (definition.version() == null
                || definition.version().isBlank()) {
            throw new IllegalArgumentException(
                    "Semantic concept alias version is required"
            );
        }

        for (SemanticConceptAliasDefinition.Entry entry :
                definition.entries()) {
            if (entry == null
                    || entry.conceptId() == null
                    || entry.conceptId().isBlank()) {
                throw new IllegalArgumentException(
                        "Semantic concept alias id is required"
                );
            }
            catalog.require(entry.conceptId());

            for (var languageAliases : entry.aliases().entrySet()) {
                String language = languageAliases.getKey();
                List<SemanticConceptSurface> current =
                        surfaces.get(language);
                if (current == null) {
                    throw new IllegalArgumentException(
                            "Semantic aliases target unsupported language: "
                                    + language
                    );
                }

                SemanticMorphologyNormalizer normalizer =
                        morphologyRegistry.require(language);
                List<String> normalizedAliases =
                        normalizeAliases(
                                languageAliases.getValue(),
                                normalizer
                        );
                if (normalizedAliases.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Semantic alias list must not be empty for "
                                    + language
                                    + "/"
                                    + entry.conceptId()
                    );
                }

                boolean found = false;
                ArrayList<SemanticConceptSurface> updated =
                        new ArrayList<>(current.size());
                for (SemanticConceptSurface existing : current) {
                    if (!existing.conceptId().equals(entry.conceptId())) {
                        updated.add(existing);
                        continue;
                    }

                    found = true;
                    LinkedHashSet<String> aliases =
                            new LinkedHashSet<>(existing.aliases());
                    for (String alias : normalizedAliases) {
                        if (!alias.equals(existing.preferredPhrase())) {
                            aliases.add(alias);
                        }
                    }
                    updated.add(new SemanticConceptSurface(
                            existing.conceptId(),
                            existing.domainId(),
                            existing.subdomainId(),
                            existing.language(),
                            existing.preferredPhrase(),
                            existing.lemmaPhrase(),
                            List.copyOf(aliases),
                            existing.stemTokens()
                    ));
                }

                if (!found) {
                    throw new IllegalArgumentException(
                            "Semantic alias concept is missing from surface pack: "
                                    + language
                                    + "/"
                                    + entry.conceptId()
                    );
                }
                surfaces.put(language, List.copyOf(updated));
            }
        }
    }

    private List<String> normalizeAliases(
            List<String> aliases,
            SemanticMorphologyNormalizer normalizer
    ) {
        return (aliases == null ? List.<String>of() : aliases).stream()
                .map(value -> String.join(
                        " ",
                        normalizer.normalizeTokens(value)
                ))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private SemanticConceptSurface surface(
            SemanticConcept concept,
            String language,
            String preferredPhrase,
            List<String> aliases,
            SemanticMorphologyNormalizer normalizer
    ) {
        String normalizedPreferred = String.join(
                " ",
                normalizer.normalizeTokens(preferredPhrase)
        );
        if (normalizedPreferred.isBlank()) {
            throw new IllegalArgumentException(
                    "Blank semantic concept surface for "
                            + language
                            + "/"
                            + concept.id()
            );
        }

        List<String> normalizedAliases = aliases.stream()
                .map(value -> String.join(
                        " ",
                        normalizer.normalizeTokens(value)
                ))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        return new SemanticConceptSurface(
                concept.id(),
                concept.domainId(),
                concept.subdomainId(),
                language,
                normalizedPreferred,
                normalizer.lemmaPhrase(normalizedPreferred),
                normalizedAliases,
                normalizer.stemTokens(normalizedPreferred)
        );
    }

    private static List<MultilingualConceptSurfaceDefinition>
            loadMultilingualDefinitions() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return MULTILINGUAL_RESOURCES.stream()
                .map(resource -> loadDefinition(mapper, resource))
                .toList();
    }

    private static MultilingualConceptSurfaceDefinition loadDefinition(
            ObjectMapper mapper,
            String resource
    ) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return mapper.readValue(
                    input,
                    MultilingualConceptSurfaceDefinition.class
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot load multilingual semantic surfaces " + resource,
                    exception
            );
        }
    }

    private static SemanticConceptAliasDefinition loadAliasDefinition() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ArrayList<SemanticConceptAliasDefinition.Entry> entries =
                new ArrayList<>();
        ArrayList<String> versions = new ArrayList<>();

        for (String resource : ALIAS_RESOURCES) {
            SemanticConceptAliasDefinition definition =
                    loadAliasDefinition(mapper, resource);
            versions.add(definition.version());
            entries.addAll(definition.entries());
        }

        return new SemanticConceptAliasDefinition(
                String.join("+", versions),
                entries
        );
    }

    private static SemanticConceptAliasDefinition loadAliasDefinition(
            ObjectMapper mapper,
            String resource
    ) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return mapper.readValue(
                    input,
                    SemanticConceptAliasDefinition.class
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot load semantic concept aliases " + resource,
                    exception
            );
        }
    }
}
