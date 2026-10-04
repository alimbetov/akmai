package kz.alimbetov.akmai.knowledge.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SemanticDomainCatalog {

    private static final String RESOURCE = "semantic/domains-v2.yaml";
    private static final Set<String> LANGUAGES = Arrays.stream(
                    KnowledgeLanguage.values()
            )
            .filter(language -> language != KnowledgeLanguage.UNKNOWN)
            .map(KnowledgeLanguage::code)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final String version;
    private final Map<String, SemanticDomainDefinition> byId;

    public SemanticDomainCatalog() {
        this(loadDefinition());
    }

    SemanticDomainCatalog(SemanticDomainCatalogDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "semantic domain catalog must not be null"
            );
        }
        this.version = definition.version();

        LinkedHashMap<String, SemanticDomainDefinition> domains =
                new LinkedHashMap<>();
        for (SemanticDomainDefinition domain : definition.domains()) {
            validate(domain);
            if (domains.putIfAbsent(domain.id(), domain) != null) {
                throw new IllegalArgumentException(
                        "Duplicate semantic domain id: " + domain.id()
                );
            }
        }
        if (domains.size() <= 12) {
            throw new IllegalArgumentException(
                    "semantic domain catalog must contain more than 12 domains"
            );
        }
        this.byId = Map.copyOf(domains);
    }

    public String version() {
        return version;
    }

    public List<SemanticDomainDefinition> domains() {
        return List.copyOf(byId.values());
    }

    public SemanticDomainDefinition require(String id) {
        SemanticDomainDefinition domain = byId.get(id);
        if (domain == null) {
            throw new IllegalArgumentException(
                    "Unknown semantic domain: " + id
            );
        }
        return domain;
    }

    public Set<String> domainIds() {
        return byId.keySet();
    }

    public Set<String> languages() {
        return LANGUAGES;
    }

    private void validate(SemanticDomainDefinition domain) {
        if (!domain.names().keySet().equals(LANGUAGES)) {
            throw new IllegalArgumentException(
                    "semantic domain names must cover every supported language: "
                            + domain.id()
            );
        }
        if (!domain.anchors().keySet().equals(LANGUAGES)) {
            throw new IllegalArgumentException(
                    "semantic domain anchors must cover every supported language: "
                            + domain.id()
            );
        }
        domain.names().forEach((language, name) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "Blank semantic domain name: "
                                + domain.id() + "/" + language
                );
            }
        });
        domain.anchors().forEach((language, anchors) -> {
            if (anchors.size() < 3) {
                throw new IllegalArgumentException(
                        "semantic domain requires at least 3 anchors: "
                                + domain.id() + "/" + language
                );
            }
            if (anchors.stream().anyMatch(
                    value -> value == null || value.isBlank()
            )) {
                throw new IllegalArgumentException(
                        "Blank semantic anchor: "
                                + domain.id() + "/" + language
                );
            }
        });
    }

    private static SemanticDomainCatalogDefinition loadDefinition() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readValue(
                    input,
                    SemanticDomainCatalogDefinition.class
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot load semantic domain catalog " + RESOURCE,
                    exception
            );
        }
    }
}
