package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import org.junit.jupiter.api.Test;

class SemanticQueryAnalyzerTest {

    private final SemanticQueryAnalyzer analyzer = analyzer();

    @Test
    void resolvesShortEnglishDomainPhraseEvenWhenGenericDetectorIsAmbiguous() {
        SemanticQueryAnalysis analysis =
                analyzer.analyze("capital adequacy ratio");

        assertThat(analysis.semanticLanguage()).isEqualTo("en");
        assertThat(analysis.confidence()).isEqualTo(1.0);
        assertThat(analysis.domains()).contains("finance_banking");
        assertThat(analysis.concepts())
                .extracting(SemanticConceptMatch::conceptId)
                .contains(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
    }

    @Test
    void resolvesRussianProfessionalPhraseFromCyrillicCandidates() {
        SemanticQueryAnalysis analysis =
                analyzer.analyze(
                        "коэффициент достаточности капитала"
                );

        assertThat(analysis.semanticLanguage()).isEqualTo("ru");
        assertThat(analysis.domains()).contains("finance_banking");
        assertThat(analysis.concepts())
                .extracting(SemanticConceptMatch::conceptId)
                .contains(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
    }

    @Test
    void resolvesKazakhPhraseUsingLanguageSpecificSurface() {
        SemanticQueryAnalysis analysis =
                analyzer.analyze("машиналық оқыту моделі");

        assertThat(analysis.semanticLanguage()).isEqualTo("kk");
        assertThat(analysis.domains())
                .contains("computer_science_ai");
        assertThat(analysis.concepts())
                .extracting(SemanticConceptMatch::conceptId)
                .contains(
                        "computer_science_ai.machine_learning.machine_learning_model"
                );
    }

    @Test
    void resolvesChinesePhraseWithoutWhitespace() {
        SemanticQueryAnalysis analysis =
                analyzer.analyze("资本充足率如何计算");

        assertThat(analysis.semanticLanguage()).isEqualTo("zh");
        assertThat(analysis.domains()).contains("finance_banking");
        assertThat(analysis.concepts())
                .extracting(SemanticConceptMatch::conceptId)
                .contains(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
    }

    @Test
    void allSupportedLanguagesResolveToSameCanonicalConcept() {
        String en = conceptId("capital adequacy ratio");
        String ru = conceptId(
                "коэффициент достаточности капитала"
        );
        String kk = conceptId(
                "капитал жеткіліктілігінің коэффициенті"
        );
        String zh = conceptId("资本充足率");

        String de = conceptId("Kapitaladäquanzquote");
        String fr = conceptId("ratio adéquation des fonds propres");
        String es = conceptId("ratio de adecuación de capital");
        String pt = conceptId("índice de adequação de capital");
        String it = conceptId("coefficiente di adeguatezza patrimoniale");
        String tr = conceptId("sermaye yeterlilik oranı");
        String el = conceptId("δείκτης κεφαλαιακής επάρκειας");

        assertThat(en)
                .isEqualTo(ru)
                .isEqualTo(kk)
                .isEqualTo(zh)
                .isEqualTo(de)
                .isEqualTo(fr)
                .isEqualTo(es)
                .isEqualTo(pt)
                .isEqualTo(it)
                .isEqualTo(tr)
                .isEqualTo(el);
    }

    @Test
    void resolvesGermanAndFrenchProfessionalPhrases() {
        assertThat(conceptId("Kapitaladäquanzquote"))
                .isEqualTo(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
        assertThat(conceptId("ratio adéquation des fonds propres"))
                .isEqualTo(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
        assertThat(conceptId("ratio de adecuación de capital"))
                .isEqualTo(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
        assertThat(conceptId("índice de adequação de capital"))
                .isEqualTo(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
        assertThat(conceptId("coefficiente di adeguatezza patrimoniale"))
                .isEqualTo(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
        assertThat(conceptId("sermaye yeterlilik oranı"))
                .isEqualTo(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
        assertThat(conceptId("δείκτης κεφαλαιακής επάρκειας"))
                .isEqualTo(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
    }

    @Test
    void genericTextDoesNotInventSemanticConcept() {
        SemanticQueryAnalysis analysis =
                analyzer.analyze(
                        "Please summarize the following document."
                );

        assertThat(analysis.hasConcepts()).isFalse();
    }

    private String conceptId(String query) {
        return analyzer.analyze(query)
                .concepts()
                .stream()
                .findFirst()
                .orElseThrow()
                .conceptId();
    }

    private SemanticQueryAnalyzer analyzer() {
        SemanticDomainCatalog domainCatalog =
                new SemanticDomainCatalog();
        EnglishSemanticConceptCatalog conceptCatalog =
                new EnglishSemanticConceptCatalog(domainCatalog);
        SemanticMorphologyRegistry morphology =
                SemanticTestMorphology.registry();
        SemanticConceptMatcher matcher =
                new SemanticConceptMatcher(
                        new SemanticConceptSurfaceRegistry(
                                conceptCatalog,
                                morphology
                        ),
                        morphology
                );
        return new SemanticQueryAnalyzer(
                new QueryLanguageDetector(),
                matcher,
                new SemanticDomainRouter(domainCatalog)
        );
    }
}
