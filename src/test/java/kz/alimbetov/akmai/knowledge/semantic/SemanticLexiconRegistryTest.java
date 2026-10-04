package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SemanticLexiconRegistryTest {

    private final SemanticDomainCatalog catalog =
            new SemanticDomainCatalog();
    private final SemanticLexiconRegistry registry =
            new SemanticLexiconRegistry(catalog);

    @Test
    void everyDomainLanguagePairContainsMoreThanOneThousandSemanticTerms() {
        long total = 0L;

        for (SemanticDomainDefinition domain : catalog.domains()) {
            for (String language : catalog.languages()) {
                int count = registry.entryCount(domain.id(), language);
                assertThat(count)
                        .as(domain.id() + "/" + language)
                        .isGreaterThan(
                                SemanticLexiconRegistry.MIN_TERMS_PER_DOMAIN_LANGUAGE
                        );
                total += count;
            }
        }

        assertThat(total)
                .as("total semantic lexicon size")
                .isGreaterThan(176_000L);
    }

    @Test
    void anchorsRemainHighWeightWhileGeneratedEnrichmentIsLowWeight() {
        var lexemes = registry.lexemes("finance_banking", "en");

        assertThat(lexemes)
                .anySatisfy(value -> {
                    assertThat(value.anchor()).isTrue();
                    assertThat(value.weight()).isEqualTo(1.0);
                    assertThat(value.surface()).isEqualTo("bank");
                })
                .anySatisfy(value -> {
                    assertThat(value.anchor()).isFalse();
                    assertThat(value.weight()).isEqualTo(0.35);
                    assertThat(value.surface())
                            .contains("bank");
                });
    }

    @Test
    void lexiconGenerationIsCachedPerDomainLanguagePair() {
        assertThat(registry.lexemes("physics_astronomy", "de"))
                .isSameAs(registry.lexemes("physics_astronomy", "de"));
    }
}
