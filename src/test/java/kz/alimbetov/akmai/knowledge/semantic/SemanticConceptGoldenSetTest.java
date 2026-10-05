package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SemanticConceptGoldenSetTest {

    private final SemanticQueryAnalyzer analyzer = analyzer();

    @Test
    void goldenSetResolvesExpectedConceptsAcrossRuKkEn()
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        GoldenSet golden;
        try (var input = new ClassPathResource(
                "quality/semantic-concept-golden-v1.json"
        ).getInputStream()) {
            golden = mapper.readValue(input, GoldenSet.class);
        }

        assertThat(golden.version())
                .isEqualTo("semantic-concept-golden-v1");
        assertThat(golden.cases()).hasSize(120);

        Map<String, Long> byLanguage = golden.cases().stream()
                .collect(Collectors.groupingBy(
                        GoldenCase::language,
                        Collectors.counting()
                ));
        assertThat(byLanguage)
                .containsEntry("en", 40L)
                .containsEntry("ru", 40L)
                .containsEntry("kk", 40L);

        assertThat(golden.cases())
                .extracting(GoldenCase::caseId)
                .doesNotHaveDuplicates();
        assertThat(golden.cases().stream()
                .map(value -> value.expectedConceptId().split("\\.")[0])
                .distinct()
                .toList())
                .as("root-domain coverage")
                .hasSize(16);

        for (GoldenCase value : golden.cases()) {
            SemanticQueryAnalysis analysis =
                    analyzer.analyze(value.query());

            assertThat(analysis.semanticLanguage())
                    .as(value.caseId() + "/language")
                    .isEqualTo(value.language());
            assertThat(analysis.concepts())
                    .as(value.caseId() + "/concept")
                    .extracting(SemanticConceptMatch::conceptId)
                    .contains(value.expectedConceptId());
        }
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

    private record GoldenSet(
            String version,
            List<GoldenCase> cases
    ) {
        private GoldenSet {
            cases = List.copyOf(cases == null ? List.of() : cases);
        }
    }

    private record GoldenCase(
            String caseId,
            String language,
            String query,
            String expectedConceptId
    ) {
    }
}
