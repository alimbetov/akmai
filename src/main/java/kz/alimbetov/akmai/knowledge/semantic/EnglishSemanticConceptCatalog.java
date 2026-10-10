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

    private static final String BASE_RESOURCE = "semantic/concepts-en-v1.yaml";
    private static final List<String> SUPPLEMENTAL_RESOURCES = List.of(
            "semantic/concepts-en-finance-batch-a-v2.yaml",
            "semantic/concepts-en-finance-batch-b-v2.yaml",
            "semantic/concepts-en-finance-batch-c-v2.yaml",
            "semantic/concepts-en-finance-batch-d-v2.yaml",
            "semantic/concepts-en-insurance-batch-a-v2.yaml",
            "semantic/concepts-en-insurance-batch-b-v2.yaml",
            "semantic/concepts-en-insurance-batch-c-v2.yaml",
            "semantic/concepts-en-insurance-batch-d-v2.yaml",
            "semantic/concepts-en-energy-utilities-batch-a-v2.yaml",
            "semantic/concepts-en-oil-gas-mining-batch-a-v2.yaml",
            "semantic/concepts-en-manufacturing-batch-a-v2.yaml",
            "semantic/concepts-en-construction-real-estate-batch-a-v2.yaml",
            "semantic/concepts-en-transport-logistics-batch-a-v2.yaml",
            "semantic/concepts-en-agriculture-food-batch-a-v2.yaml"
    );
    private static final String COMBINED_VERSION = "semantic-concepts-en-v2";

    private final String version;
    private final List<SemanticConcept> concepts;
    private final Map<String, SemanticConcept> byId;

    public EnglishSemanticConceptCatalog(SemanticDomainCatalog domainCatalog) {
        this(domainCatalog, loadDefinition());
    }

    EnglishSemanticConceptCatalog(
            SemanticDomainCatalog domainCatalog,
            EnglishConceptCorpusDefinition definition
    ) {
        if (definition == null || definition.version() == null || definition.version().isBlank()) {
            throw new IllegalArgumentException("English semantic concept corpus version is required");
        }
        this.version = definition.version();

        LinkedHashMap<String, SemanticConcept> conceptsById = new LinkedHashMap<>();
        LinkedHashMap<String, String> phraseOwners = new LinkedHashMap<>();
        LinkedHashSet<String> coveredDomains = new LinkedHashSet<>();

        for (var domain : definition.domains()) {
            domainCatalog.require(domain.domainId());
            if (!coveredDomains.add(domain.domainId())) {
                throw new IllegalArgumentException("Duplicate concept domain: " + domain.domainId());
            }

            Set<String> subdomainIds = new LinkedHashSet<>();
            for (var subdomain : domain.subdomains()) {
                validateSubdomainId(subdomain.id());
                if (!subdomainIds.add(subdomain.id())) {
                    throw new IllegalArgumentException(
                            "Duplicate semantic subdomain: " + domain.domainId() + "/" + subdomain.id()
                    );
                }

                for (String phrase : subdomain.phrases()) {
                    String normalized = normalizePhrase(phrase);
                    validatePhrase(domain.domainId(), subdomain.id(), normalized);

                    String owner = domain.domainId() + "/" + subdomain.id();
                    String existingOwner = phraseOwners.putIfAbsent(normalized, owner);
                    if (existingOwner != null) {
                        throw new IllegalArgumentException(
                                "Duplicate canonical semantic phrase after normalization: '"
                                        + normalized + "' owned by " + existingOwner + " and " + owner
                        );
                    }

                    String id = conceptId(domain.domainId(), subdomain.id(), normalized);
                    SemanticConcept concept = new SemanticConcept(
                            id,
                            domain.domainId(),
                            subdomain.id(),
                            normalized
                    );
                    if (conceptsById.putIfAbsent(id, concept) != null) {
                        throw new IllegalArgumentException("Duplicate semantic concept id: " + id);
                    }
                }
            }
        }

        if (!coveredDomains.equals(domainCatalog.domainIds())) {
            throw new IllegalArgumentException(
                    "English semantic concept corpus must cover every domain; expected="
                            + domainCatalog.domainIds() + ", actual=" + coveredDomains
            );
        }

        this.byId = Map.copyOf(conceptsById);
        this.concepts = conceptsById.values().stream()
                .sorted(
                        Comparator.comparingInt(
                                        (SemanticConcept concept) -> tokenCount(concept.preferredPhrase())
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
            throw new IllegalArgumentException("Unknown semantic concept: " + id);
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

    private static void validatePhrase(String domainId, String subdomainId, String phrase) {
        int tokens = tokenCount(phrase);
        if (tokens < 2 || tokens > 6) {
            throw new IllegalArgumentException(
                    "primary semantic concepts must contain 2-6 words: "
                            + domainId + "/" + subdomainId + " -> " + phrase
            );
        }
    }

    static String normalizePhrase(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
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

    private static String conceptId(String domainId, String subdomainId, String phrase) {
        return domainId + "." + subdomainId + "." + phrase.replace(' ', '_');
    }

    private static EnglishConceptCorpusDefinition loadDefinition() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        EnglishConceptCorpusDefinition base = loadDefinition(mapper, BASE_RESOURCE);
        List<EnglishConceptCorpusDefinition> supplements = SUPPLEMENTAL_RESOURCES.stream()
                .map(resource -> loadDefinition(mapper, resource))
                .toList();
        return mergeDefinitions(base, supplements);
    }

    private static EnglishConceptCorpusDefinition mergeDefinitions(
            EnglishConceptCorpusDefinition base,
            List<EnglishConceptCorpusDefinition> supplements
    ) {
        LinkedHashMap<String, LinkedHashMap<String, List<String>>> merged = new LinkedHashMap<>();
        appendDefinition(merged, base);
        for (EnglishConceptCorpusDefinition supplement : supplements) {
            appendDefinition(merged, supplement);
        }

        List<EnglishConceptCorpusDefinition.DomainConcepts> domains = merged.entrySet().stream()
                .map(domainEntry -> new EnglishConceptCorpusDefinition.DomainConcepts(
                        domainEntry.getKey(),
                        domainEntry.getValue().entrySet().stream()
                                .map(subdomainEntry -> new EnglishConceptCorpusDefinition.SubdomainConcepts(
                                        subdomainEntry.getKey(),
                                        subdomainEntry.getValue()
                                ))
                                .toList()
                ))
                .toList();

        return new EnglishConceptCorpusDefinition(COMBINED_VERSION, domains);
    }

    private static void appendDefinition(
            LinkedHashMap<String, LinkedHashMap<String, List<String>>> merged,
            EnglishConceptCorpusDefinition definition
    ) {
        if (definition == null || definition.version() == null || definition.version().isBlank()) {
            throw new IllegalArgumentException("English semantic concept corpus version is required");
        }
        for (var domain : definition.domains()) {
            LinkedHashMap<String, List<String>> subdomains = merged.computeIfAbsent(
                    domain.domainId(),
                    ignored -> new LinkedHashMap<>()
            );
            for (var subdomain : domain.subdomains()) {
                List<String> current = subdomains.get(subdomain.id());
                if (current == null) {
                    subdomains.put(subdomain.id(), List.copyOf(subdomain.phrases()));
                    continue;
                }
                ArrayList<String> combined = new ArrayList<>(current);
                combined.addAll(subdomain.phrases());
                subdomains.put(subdomain.id(), List.copyOf(combined));
            }
        }
    }

    private static EnglishConceptCorpusDefinition loadDefinition(ObjectMapper mapper, String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return mapper.readValue(input, EnglishConceptCorpusDefinition.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot load English semantic concept corpus " + resource,
                    exception
            );
        }
    }
}
