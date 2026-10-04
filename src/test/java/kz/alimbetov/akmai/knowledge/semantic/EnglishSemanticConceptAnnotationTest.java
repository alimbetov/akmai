package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class EnglishSemanticConceptAnnotationTest {

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final SemanticChunkAnnotator annotator =
            new SemanticChunkAnnotator(
                    new SemanticDomainRouter(domainCatalog),
                    matcher()
            );

    @Test
    void phraseConceptCanDriveDomainAnnotationWithoutAnchorWord() {
        KnowledgeChunk annotated = annotator.annotate(chunk(
                "The capital adequacy ratio exceeded the regulatory minimum.",
                "en"
        ));

        assertThat(annotated.metadata().get("semanticDomains"))
                .asList()
                .contains("finance_banking");
        assertThat(annotated.metadata().get("semanticConcepts"))
                .asList()
                .contains(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
        assertThat(annotated.metadata())
                .containsEntry(
                        "semanticConceptVersion",
                        "semantic-concepts-en-v1"
                );
    }

    @Test
    void conceptPhrasesArePersistedAsExplainableMetadata() {
        KnowledgeChunk annotated = annotator.annotate(chunk(
                "Natural language processing uses a large language model.",
                "en"
        ));

        assertThat(annotated.metadata().get("semanticConceptPhrases"))
                .asList()
                .contains(
                        "natural language processing",
                        "large language model"
                );
    }

    @Test
    void EnglishPhraseDoesNotLeakIntoRussianSurfaceMatching() {
        KnowledgeChunk original = chunk(
                "capital adequacy ratio",
                "ru"
        );

        KnowledgeChunk annotated = annotator.annotate(original);

        assertThat(annotated.metadata())
                .doesNotContainKey("semanticConcepts");
    }

    private SemanticConceptMatcher matcher() {
        SemanticMorphologyRegistry morphology =
                new SemanticMorphologyRegistry(
                        List.of(
                                new EnglishSemanticMorphologyNormalizer(),
                                new RussianSemanticMorphologyNormalizer(),
                                new KazakhSemanticMorphologyNormalizer()
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
