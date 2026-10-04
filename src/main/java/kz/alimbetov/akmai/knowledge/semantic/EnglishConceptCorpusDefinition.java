package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record EnglishConceptCorpusDefinition(
        String version,
        List<DomainConcepts> domains
) {
    public EnglishConceptCorpusDefinition {
        domains = List.copyOf(domains == null ? List.of() : domains);
    }

    public record DomainConcepts(
            String domainId,
            List<SubdomainConcepts> subdomains
    ) {
        public DomainConcepts {
            subdomains = List.copyOf(
                    subdomains == null ? List.of() : subdomains
            );
        }
    }

    public record SubdomainConcepts(
            String id,
            List<String> phrases
    ) {
        public SubdomainConcepts {
            phrases = List.copyOf(phrases == null ? List.of() : phrases);
        }
    }
}
