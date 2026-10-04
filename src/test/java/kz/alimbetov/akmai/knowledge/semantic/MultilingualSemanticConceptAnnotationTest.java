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
    private final SemanticChunkAnnotator annotator =
            new SemanticChunkAnnotator(
                    new SemanticDomainRouter(domainCatalog),
                    matcher()
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
                        "semantic-surfaces-ru-v1"
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
                        "semantic-surfaces-kk-v1"
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
                        "semantic-surfaces-zh-v1"
                );
    }

    private SemanticConceptMatcher matcher() {
        SemanticMorphologyRegistry morphology =
                new SemanticMorphologyRegistry(
                        List.of(
                                new EnglishSemanticMorphologyNormalizer(),
                                new RussianSemanticMorphologyNormalizer(),
                                new KazakhSemanticMorphologyNormalizer(),
                                new ChineseSemanticMorphologyNormalizer()
                        )
                );
        return new SemanticConceptMatcher(
                new SemanticConceptSurfaceRegistry(
                        new EnglishSemanticConceptCatalog(
                                domainCatalog
                        ),
                        morphology
                ),
                morphology
        );
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
