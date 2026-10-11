package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class MultilingualSemanticConceptAnnotationTest {

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final SemanticMorphologyRegistry morphology =
            SemanticTestMorphology.registry();
    private final SemanticConceptSurfaceRegistry surfaceRegistry =
            new SemanticConceptSurfaceRegistry(
                    new EnglishSemanticConceptCatalog(domainCatalog),
                    morphology
            );
    private final SemanticChunkAnnotator annotator =
            new SemanticChunkAnnotator(
                    new SemanticDomainRouter(domainCatalog),
                    new SemanticConceptMatcher(
                            surfaceRegistry,
                            morphology
                    )
            );

    @Test
    void russianChunkGetsCanonicalConceptMetadata() {
        KnowledgeChunk annotated = annotator.annotate(chunk(
                "Оценка воздействия на окружающую среду обязательна.",
                "ru"
        ));

        assertThat(annotated.metadata().get("semanticConcepts"))
                .asList()
                .contains(
                        "earth_environmental_science.environmental_assessment.environmental_impact_assessment"
                );
        assertThat(annotated.metadata())
                .containsEntry(
                        "semanticConceptVersion",
                        surfaceRegistry.version("ru")
                );
    }

    @Test
    void kazakhChunkGetsSameCanonicalOntologyCoordinates() {
        KnowledgeChunk annotated = annotator.annotate(chunk(
                "Табиғи тілді өңдеу машиналық оқытуға сүйенеді.",
                "kk"
        ));

        assertThat(annotated.metadata().get("semanticConcepts"))
                .asList()
                .contains(
                        "computer_science_ai.data_nlp.natural_language_processing"
                );
        assertThat(annotated.metadata().get("semanticDomains"))
                .asList()
                .contains("computer_science_ai");
        assertThat(annotated.metadata())
                .containsEntry(
                        "semanticConceptVersion",
                        surfaceRegistry.version("kk")
                );
    }

    @Test
    void chineseChunkGetsCanonicalConceptMetadataWithoutWhitespace() {
        KnowledgeChunk annotated = annotator.annotate(chunk(
                "自然语言处理系统使用大语言模型生成答案。",
                "zh"
        ));

        assertThat(annotated.metadata().get("semanticConcepts"))
                .asList()
                .contains(
                        "computer_science_ai.data_nlp.natural_language_processing",
                        "computer_science_ai.data_nlp.large_language_model"
                );
        assertThat(annotated.metadata())
                .containsEntry(
                        "semanticConceptVersion",
                        surfaceRegistry.version("zh")
                );
    }

    @Test
    void europeanAndTurkishGreekChunksPersistCanonicalMetadata() {
        List<LanguageCase> cases = List.of(
                new LanguageCase(
                        "de",
                        "Die Kapitaladäquanzquote bleibt stabil."
                ),
                new LanguageCase(
                        "fr",
                        "Le ratio adéquation des fonds propres reste solide."
                ),
                new LanguageCase(
                        "es",
                        "El ratio de adecuación de capital sigue estable."
                ),
                new LanguageCase(
                        "pt",
                        "O índice de adequação de capital permanece sólido."
                ),
                new LanguageCase(
                        "it",
                        "Il coefficiente di adeguatezza patrimoniale resta solido."
                ),
                new LanguageCase(
                        "tr",
                        "Sermaye yeterlilik oranı güçlü kalmaktadır."
                ),
                new LanguageCase(
                        "el",
                        "Ο δείκτης κεφαλαιακής επάρκειας παραμένει ισχυρός."
                )
        );

        for (LanguageCase value : cases) {
            KnowledgeChunk annotated = annotator.annotate(
                    chunk(value.text(), value.language())
            );

            assertThat(annotated.metadata().get("semanticConcepts"))
                    .as(value.language())
                    .asList()
                    .contains(
                            "finance_banking.risk_capital.capital_adequacy_ratio"
                    );
            assertThat(annotated.metadata())
                    .as(value.language())
                    .containsEntry(
                            "semanticConceptVersion",
                            surfaceRegistry.version(value.language())
                    );
        }
    }

    private record LanguageCase(
            String language,
            String text
    ) {
    }

    private KnowledgeChunk chunk(String text, String language) {
        return new KnowledgeChunk(
                "chunk-1",
                "doc-1",
                null,
                0,
                text,
                text,
                text,
                "Title",
                "Section",
                language,
                KnowledgeDomain.GENERAL,
                List.of(),
                Map.of("source", "source.md")
        );
    }
}
