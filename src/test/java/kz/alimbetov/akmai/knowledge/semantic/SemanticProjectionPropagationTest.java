package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import org.junit.jupiter.api.Test;

class SemanticProjectionPropagationTest {

    @Test
    void semanticAnnotationsFlowIntoSearchProjectionMetadata() {
        KnowledgeChunk chunk = new KnowledgeChunk(
                "chunk-1",
                "doc-1",
                null,
                0,
                "Interest rate and bank credit risk.",
                "Interest rate and bank credit risk.",
                "Interest rate and bank credit risk.",
                "Banking",
                "Risk",
                "en",
                KnowledgeDomain.GENERAL,
                List.of(),
                Map.of(
                        "semanticOntologyVersion", "semantic-domain-v2",
                        "semanticDomains", List.of("finance_banking"),
                        "semanticConceptVersion", "semantic-concepts-en-v1",
                        "semanticConcepts", List.of(
                                "finance_banking.risk_capital.capital_adequacy_ratio"
                        ),
                        "semanticConceptPhrases", List.of(
                                "capital adequacy ratio"
                        )
                )
        );

        var projection = new SearchProjectionFactory().create(
                new EnrichedKnowledgeChunk(
                        chunk,
                        List.of(),
                        List.of()
                )
        );

        assertThat(projection.metadata())
                .containsEntry(
                        "semanticOntologyVersion",
                        "semantic-domain-v2"
                );
        assertThat(projection.metadata().get("semanticDomains"))
                .isEqualTo(List.of("finance_banking"));
        assertThat(projection.metadata().get("semanticConcepts"))
                .isEqualTo(List.of(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                ));
        assertThat(projection.metadata().get("semanticConceptPhrases"))
                .isEqualTo(List.of("capital adequacy ratio"));
    }
}
