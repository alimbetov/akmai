package kz.alimbetov.akmai.knowledge.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
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
            "semantic/concept-surfaces-pt-v1.yaml"
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
                List.of()
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
                loadMultilingualDefinitions()
        );
    }

    SemanticConceptSurfaceRegistry(
            EnglishSemanticConceptCatalog catalog,
            SemanticMorphologyRegistry morphologyRegistry,
            List<MultilingualConceptSurfaceDefinition> definitions
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
                    "Cannot load multilingual semantic surfaces "
                            + resource,
                    exception
            );
        }
    }
}
