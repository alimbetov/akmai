package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KazakhSemanticSurfaceCoverageTest {

    private static final Set<String> ROLLOUT_DOMAINS = Set.of(
            "finance_banking",
            "insurance",
            "energy_utilities",
            "oil_gas_mining",
            "manufacturing",
            "construction_real_estate",
            "transport_logistics"
    );
    private static final int BASELINE_KK_SURFACES = 384;
    private static final int ADDED_SURFACES_PER_DOMAIN = 400;
    private static final int EXPECTED_SURFACES_PER_DOMAIN = 424;
    private static final int EXPECTED_SURFACES_PER_SUBDOMAIN = 106;
    private static final int EXPECTED_KK_SURFACES =
            BASELINE_KK_SURFACES
                    + ROLLOUT_DOMAINS.size() * ADDED_SURFACES_PER_DOMAIN;

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog conceptCatalog =
            new EnglishSemanticConceptCatalog(domainCatalog);
    private final SemanticConceptSurfaceRegistry registry =
            new SemanticConceptSurfaceRegistry(
                    conceptCatalog,
                    SemanticTestMorphology.registry()
            );

    @Test
    void kazakhRolloutProvidesExpectedDomainAndSubdomainParity() {
        List<SemanticConceptSurface> kazakh = registry.surfaces("kk");

        assertThat(kazakh)
                .as("total registered KK semantic surfaces")
                .hasSize(EXPECTED_KK_SURFACES);
        assertThat(kazakh)
                .extracting(SemanticConceptSurface::conceptId)
                .doesNotHaveDuplicates();

        Map<String, List<SemanticConceptSurface>> byDomain =
                kazakh.stream().collect(Collectors.groupingBy(
                        SemanticConceptSurface::domainId
                ));

        for (String domainId : ROLLOUT_DOMAINS) {
            List<SemanticConceptSurface> domainSurfaces =
                    byDomain.getOrDefault(domainId, List.of());
            assertThat(domainSurfaces)
                    .as(domainId + " KK surfaces")
                    .hasSize(EXPECTED_SURFACES_PER_DOMAIN);

            Map<String, Long> subdomainCounts = domainSurfaces.stream()
                    .collect(Collectors.groupingBy(
                            SemanticConceptSurface::subdomainId,
                            Collectors.counting()
                    ));
            assertThat(subdomainCounts)
                    .as(domainId + " KK subdomains")
                    .hasSize(4)
                    .allSatisfy((subdomainId, count) ->
                            assertThat(count)
                                    .as(domainId + "/" + subdomainId)
                                    .isEqualTo(
                                            (long) EXPECTED_SURFACES_PER_SUBDOMAIN
                                    )
                    );
        }
    }

    @Test
    void kazakhRolloutPreservesCanonicalReferencesAndPhraseContract() {
        List<SemanticConceptSurface> rollout = registry.surfaces("kk").stream()
                .filter(surface -> ROLLOUT_DOMAINS.contains(
                        surface.domainId()
                ))
                .toList();

        assertThat(rollout).hasSize(
                ROLLOUT_DOMAINS.size() * EXPECTED_SURFACES_PER_DOMAIN
        );

        for (SemanticConceptSurface surface : rollout) {
            SemanticConcept canonical =
                    conceptCatalog.require(surface.conceptId());

            assertThat(surface.domainId())
                    .as(surface.conceptId() + " domain")
                    .isEqualTo(canonical.domainId());
            assertThat(surface.subdomainId())
                    .as(surface.conceptId() + " subdomain")
                    .isEqualTo(canonical.subdomainId());

            int wordCount = surface.preferredPhrase()
                    .trim()
                    .split("\\s+")
                    .length;
            assertThat(wordCount)
                    .as(surface.conceptId()
                            + " preferredPhrase='"
                            + surface.preferredPhrase()
                            + "'")
                    .isBetween(2, 6);
        }
    }
}
