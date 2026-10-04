package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SemanticDomainCatalogTest {

    private final SemanticDomainCatalog catalog =
            new SemanticDomainCatalog();

    @Test
    void catalogContainsBalancedEconomicAndScientificDomains() {
        Map<SemanticDomainKind, Long> counts = catalog.domains().stream()
                .collect(Collectors.groupingBy(
                        SemanticDomainDefinition::kind,
                        Collectors.counting()
                ));

        assertThat(catalog.domains()).hasSize(16);
        assertThat(counts.get(SemanticDomainKind.ECONOMIC_SECTOR))
                .isEqualTo(8L);
        assertThat(counts.get(SemanticDomainKind.SCIENTIFIC_DISCIPLINE))
                .isEqualTo(8L);
    }

    @Test
    void everyDomainCoversAllElevenLanguagesWithAtLeastThreeAnchors() {
        assertThat(catalog.languages()).hasSize(11);

        catalog.domains().forEach(domain -> {
            assertThat(domain.names().keySet())
                    .as(domain.id() + "/names")
                    .containsExactlyInAnyOrderElementsOf(catalog.languages());
            assertThat(domain.anchors().keySet())
                    .as(domain.id() + "/anchors")
                    .containsExactlyInAnyOrderElementsOf(catalog.languages());
            catalog.languages().forEach(language -> {
                assertThat(domain.names().get(language))
                        .as(domain.id() + "/" + language + "/name")
                        .isNotBlank();
                assertThat(domain.anchors().get(language))
                        .as(domain.id() + "/" + language + "/anchors")
                        .hasSizeGreaterThanOrEqualTo(3)
                        .allSatisfy(value -> assertThat(value).isNotBlank());
            });
        });
    }
}
