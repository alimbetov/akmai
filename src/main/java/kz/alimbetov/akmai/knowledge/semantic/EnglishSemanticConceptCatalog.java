package kz.alimbetov.akmai.knowledge.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class EnglishSemanticConceptCatalog {

    private static final String RESOURCE = "semantic/concepts-en-v1.yaml";

    private final String version;
    private final List<SemanticConcept> concepts;
    private final Map<String, SemanticConcept> byId;

    public EnglishSemanticConceptCatalog(
            SemanticDomainCatalog domainCatalog
    ) {
        this(domainCatalog, loadDefinition());
    }

    EnglishSemanticConceptCatalog(
            SemanticDomainCatalog domainCatalog,
            EnglishConceptCorpusDefinition definition
    ) {
        if (definition == null
                || definition.version() == null
                || definition.version().isBlank()) {
            throw new IllegalArgumentException(
                    "English semantic concept corpus version is required"
            );
        }
        this.version = definition.version();

        LinkedHashMap<String, SemanticConcept> conceptsById =
                new LinkedHashMap<>();
        LinkedHashSet<String> coveredDomains = new LinkedHashSet<>();

        for (var domain : definition.domains()) {
            domainCatalog.require(domain.domainId());
            if (!coveredDomains.add(domain.domainId())) {
                throw new IllegalArgumentException(
                        "Duplicate concept domain: " + domain.domainId()
                );
            }

            Set<String> subdomainIds = new LinkedHashSet<>();
            for (var subdomain : domain.subdomains()) {
                validateSubdomainId(subdomain.id());
                if (!subdomainIds.add(subdomain.id())) {
                    throw new IllegalArgumentException(
                            "Duplicate semantic subdomain: "
                                    + domain.domainId()
                                    + "/"
                                    + subdomain.id()
                    );
                }

                for (String phrase : subdomain.phrases()) {
                    String normalized = normalizePhrase(phrase);
                    validatePhrase(
                            domain.domainId(),
                            subdomain.id(),
                            normalized
                    );
                    String id = conceptId(
                            domain.domainId(),
                            subdomain.id(),
                            normalized
                    );
                    SemanticConcept concept = new SemanticConcept(
                            id,
                            domain.domainId(),
                            subdomain.id(),
                            normalized
                    );
                    if (conceptsById.putIfAbsent(id, concept) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate semantic concept id: " + id
                        );
                    }
                }
            }
        }

        if (!coveredDomains.equals(domainCatalog.domainIds())) {
            throw new IllegalArgumentException(
                    "English semantic concept corpus must cover every domain; expected="
                            + domainCatalog.domainIds()
                            + ", actual="
                            + coveredDomains
            );
        }

        this.byId = Map.copyOf(conceptsById);
        this.concepts = conceptsById.values().stream()
                .sorted(
                        Comparator.comparingInt(
                                        (SemanticConcept concept) ->
                                                tokenCount(
                                                        concept.preferredPhrase()
                                                )
                                )
                                .reversed()
                                .thenComparing(SemanticConcept::id)
                )
                .toList();
    }

    public String version() {
        return version;
    }

    public List<SemanticConcept> concepts() {
        return concepts;
    }

    public SemanticConcept require(String id) {
        SemanticConcept concept = byId.get(id);
        if (concept == null) {
            throw new IllegalArgumentException(
                    "Unknown semantic concept: " + id
            );
        }
        return concept;
    }

    public List<SemanticConcept> conceptsForDomain(String domainId) {
        return concepts.stream()
                .filter(concept -> concept.domainId().equals(domainId))
                .toList();
    }

    private static void validateSubdomainId(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException(
                    "semantic subdomain id must be a stable lowercase identifier"
            );
        }
    }

    private static void validatePhrase(
            String domainId,
            String subdomainId,
            String phrase
    ) {
        int tokens = tokenCount(phrase);
        if (tokens < 2 || tokens > 6) {
            throw new IllegalArgumentException(
                    "primary semantic concepts must contain 2-6 words: "
                            + domainId
                            + "/"
                            + subdomainId
                            + " -> "
                            + phrase
            );
        }
    }

    static String normalizePhrase(String value) {
        return Normalizer.normalize(
                        value == null ? "" : value,
                        Normalizer.Form.NFC
                )
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ");
    }

    private static int tokenCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return value.split("\\s+").length;
    }

    private static String conceptId(
            String domainId,
            String subdomainId,
            String phrase
    ) {
        return domainId
                + "."
                + subdomainId
                + "."
                + phrase.replace(' ', '_');
    }

    private static EnglishConceptCorpusDefinition loadDefinition() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readValue(
                    input,
                    EnglishConceptCorpusDefinition.class
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot load English semantic concept corpus " + RESOURCE,
                    exception
            );
        }
    }
}
