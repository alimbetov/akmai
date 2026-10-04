package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

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
    void germanCompoundMapsToCanonicalConceptId() {
        var match = matcher.match(
                        "Die Kapitaladäquanzquote bleibt über dem Mindestwert.",
                        "de"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
    }

    @Test
    void frenchPhraseMapsToSameCanonicalConceptId() {
        var match = matcher.match(
                        "Le ratio adéquation des fonds propres reste solide.",
                        "fr"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
    }

    @Test
    void spanishPhraseMapsToCanonicalConceptId() {
        var match = matcher.match(
                        "El ratio de adecuación de capital supera el mínimo.",
                        "es"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
    }

    @Test
    void portuguesePhraseMapsToCanonicalConceptId() {
        var match = matcher.match(
                        "O índice de adequação de capital permanece robusto.",
                        "pt"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
    }

    @Test
    void italianPhraseMapsToCanonicalConceptId() {
        var match = matcher.match(
                        "Il coefficiente di adeguatezza patrimoniale resta solido.",
                        "it"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
    }

    @Test
    void turkishPhraseMapsToCanonicalConceptId() {
        var match = matcher.match(
                        "Sermaye yeterlilik oranı düzenleyici sınırın üzerindedir.",
                        "tr"
                )
                .stream()
                .filter(value -> value.conceptId().equals(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ))
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.EXACT);
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
