package kz.alimbetov.akmai.knowledge.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SemanticAmbiguityRegistry {

    private static final String RESOURCE =
            "semantic/semantic-ambiguities-en-v1.yaml";

    private final String version;
    private final Map<String, Ambiguity> bySurface;

    public SemanticAmbiguityRegistry(
            EnglishSemanticConceptCatalog catalog
    ) {
        this(catalog, loadDefinition());
    }

    SemanticAmbiguityRegistry(
            EnglishSemanticConceptCatalog catalog,
            SemanticAmbiguityDefinition definition
    ) {
        if (definition == null
                || definition.version() == null
                || definition.version().isBlank()) {
            throw new IllegalArgumentException(
                    "Semantic ambiguity registry version is required"
            );
        }
        this.version = definition.version();

        LinkedHashMap<String, Ambiguity> entries = new LinkedHashMap<>();
        for (SemanticAmbiguityDefinition.Entry entry : definition.entries()) {
            String surface = EnglishSemanticConceptCatalog.normalizePhrase(
                    entry.surface()
            );
            if (surface.isBlank()) {
                throw new IllegalArgumentException(
                        "Semantic ambiguity surface must not be blank"
                );
            }

            LinkedHashSet<String> conceptIds =
                    new LinkedHashSet<>(entry.conceptIds());
            if (conceptIds.size() < 2) {
                throw new IllegalArgumentException(
                        "Semantic ambiguity must reference at least two concepts: "
                                + surface
                );
            }
            conceptIds.forEach(catalog::require);

            String rationale = entry.rationale() == null
                    ? ""
                    : entry.rationale().trim();
            Ambiguity ambiguity = new Ambiguity(
                    surface,
                    List.copyOf(conceptIds),
                    rationale
            );
            if (entries.putIfAbsent(surface, ambiguity) != null) {
                throw new IllegalArgumentException(
                        "Duplicate semantic ambiguity surface: " + surface
                );
            }
        }

        this.bySurface = Map.copyOf(entries);
    }

    public String version() {
        return version;
    }

    public boolean isAmbiguous(String surface) {
        return bySurface.containsKey(
                EnglishSemanticConceptCatalog.normalizePhrase(surface)
        );
    }

    public Ambiguity require(String surface) {
        String normalized = EnglishSemanticConceptCatalog.normalizePhrase(surface);
        Ambiguity ambiguity = bySurface.get(normalized);
        if (ambiguity == null) {
            throw new IllegalArgumentException(
                    "Unknown semantic ambiguity: " + normalized
            );
        }
        return ambiguity;
    }

    public List<Ambiguity> entries() {
        return bySurface.values().stream()
                .sorted((left, right) ->
                        left.surface().compareTo(right.surface()))
                .toList();
    }

    private static SemanticAmbiguityDefinition loadDefinition() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readValue(
                    input,
                    SemanticAmbiguityDefinition.class
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot load semantic ambiguity registry " + RESOURCE,
                    exception
            );
        }
    }

    public record Ambiguity(
            String surface,
            List<String> conceptIds,
            String rationale
    ) {
    }
}
