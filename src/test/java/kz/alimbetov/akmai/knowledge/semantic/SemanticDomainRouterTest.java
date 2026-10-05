package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SemanticDomainRouterTest {

    private final SemanticDomainCatalog catalog =
            new SemanticDomainCatalog();
    private final SemanticDomainRouter router =
            new SemanticDomainRouter(catalog);

    @Test
    void everyDomainIsRecognizedFromEveryLanguageUsingCuratedAnchors() {
        for (SemanticDomainDefinition domain : catalog.domains()) {
            for (String language : catalog.languages()) {
                String anchor = domain.anchors().get(language).getFirst();

                SemanticQueryProfile profile =
                        router.analyze(anchor, language);

                assertThat(profile.domains())
                        .as(domain.id() + "/" + language + "/" + anchor)
                        .isNotEmpty();
                assertThat(profile.domains().getFirst().domainId())
                        .isEqualTo(domain.id());
            }
        }
    }

    @Test
    void queryCanBelongToMultipleCompatibleDomains() {
        SemanticQueryProfile profile = router.analyze(
                "Bank credit risk and climate ecosystem exposure",
                "en"
        );

        assertThat(profile.domains())
                .extracting(SemanticDomainScore::domainId)
                .contains(
                        "finance_banking",
                        "earth_environmental_science"
                );
    }

    @Test
    void englishBoundaryMatchingDoesNotTreatBankruptcyAsBank() {
        SemanticQueryProfile profile = router.analyze(
                "Bankruptcy proceedings are described here.",
                "en"
        );

        assertThat(profile.domains())
                .extracting(SemanticDomainScore::domainId)
                .doesNotContain("finance_banking");
    }

    @Test
    void chineseRoutingUsesSubstringMatchingForContinuousScript() {
        SemanticQueryProfile profile = router.analyze(
                "本研究分析机器学习算法的性能。",
                "zh"
        );

        assertThat(profile.domains())
                .extracting(SemanticDomainScore::domainId)
                .contains("computer_science_ai");
    }

    @Test
    void genericTextDoesNotInventDomain() {
        SemanticQueryProfile profile = router.analyze(
                "Please summarize the following document.",
                "en"
        );

        assertThat(profile.domains()).isEmpty();
    }
}
