package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MultilingualSemanticConceptMatcherTest {

    private final SemanticConceptMatcher matcher = matcher();

    @Test
    void russianSurfaceMapsToEnglishCanonicalConceptId() {
        var match = matcher.match(
                        "Коэффициент достаточности капитала превышает норматив.",
                        "ru"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
        assertThat(match.domainId()).isEqualTo("finance_banking");
    }

    @Test
    void russianInflectionMatchesThroughLemmaSequence() {
        var match = matcher.match(
                        "Расчет коэффициента достаточности капитала обязателен.",
                        "ru"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.LEMMA);
    }

    @Test
    void kazakhSurfaceMapsToSameCanonicalConceptId() {
        var match = matcher.match(
                        "Машиналық оқыту моделі деректермен оқытылады.",
                        "kk"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "computer_science_ai.machine_learning.machine_learning_model"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
        assertThat(match.domainId()).isEqualTo("computer_science_ai");
    }

    @Test
    void chineseContinuousTextMatchesSegmentedCanonicalPhrase() {
        var match = matcher.match(
                        "银行必须持续满足资本充足率监管要求。",
                        "zh"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
        assertThat(match.domainId()).isEqualTo("finance_banking");
    }

    @Test
    void unsupportedLanguageCannotFallBackToAnotherSurfacePack() {
        assertThat(matcher.match(
                "коэффициент достаточности капитала",
                "en"
        ))
                .extracting(SemanticConceptMatch::conceptId)
                .doesNotContain(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );

        assertThat(matcher.match(
                "capital adequacy ratio",
                "ru"
        ))
                .extracting(SemanticConceptMatch::conceptId)
                .doesNotContain(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
    }

    private SemanticConceptMatcher matcher() {
        SemanticDomainCatalog domainCatalog =
                new SemanticDomainCatalog();
        EnglishSemanticConceptCatalog conceptCatalog =
                new EnglishSemanticConceptCatalog(domainCatalog);
        SemanticMorphologyRegistry morphology =
                SemanticTestMorphology.registry();
        return new SemanticConceptMatcher(
                new SemanticConceptSurfaceRegistry(
                        conceptCatalog,
                        morphology
                ),
                morphology
        );
    }
}
