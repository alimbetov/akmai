package kz.alimbetov.akmai.rag.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptMatch;
import kz.alimbetov.akmai.knowledge.semantic.SemanticMatchMode;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdaptiveRetrievalPlannerTest {

    @Mock
    SemanticQueryAnalyzer analyzer;

    @Test
    void exactConceptShadowOmitsRedundantLexicalLane() {
        QueryChunk chunk = query("capital adequacy ratio");
        when(analyzer.analyze(chunk.semanticText()))
                .thenReturn(new SemanticQueryAnalysis(
                        "en",
                        "en",
                        1.0,
                        List.of("finance_banking"),
                        List.of(new SemanticConceptMatch(
                                "finance_banking.risk_capital.capital_adequacy_ratio",
                                "finance_banking",
                                "risk_capital",
                                "capital adequacy ratio",
                                3.0,
                                SemanticMatchMode.EXACT
                        ))
                ));
        AdaptiveRetrievalPlanner subject = new AdaptiveRetrievalPlanner(
                analyzer,
                new AdaptiveRetrievalProperties(true, 0.65, 0.95)
        );
        RetrievalPlan current = new RetrievalPlanner().plan(List.of(chunk));

        AdaptiveRetrievalPlanner.ChunkRecommendation recommendation =
                subject.shadow(List.of(chunk), current)
                        .recommendations()
                        .getFirst();

        assertThat(recommendation.queryClass())
                .isEqualTo(AdaptiveRetrievalPlanner.QueryClass.CONCEPTUAL_EXACT);
        assertThat(recommendation.recommendedLanes())
                .containsExactlyInAnyOrder(
                        RetrievalType.VECTOR,
                        RetrievalType.CONCEPT,
                        RetrievalType.REFERENCE
                )
                .doesNotContain(RetrievalType.LEXICAL);
        assertThat(recommendation.currentLanes())
                .contains(RetrievalType.LEXICAL);
    }

    @Test
    void genericShadowOmitsConceptLaneWithoutSemanticConcepts() {
        QueryChunk chunk = query("summarize the operational requirements");
        when(analyzer.analyze(chunk.semanticText()))
                .thenReturn(new SemanticQueryAnalysis(
                        "en",
                        "en",
                        0.94,
                        List.of(),
                        List.of()
                ));
        AdaptiveRetrievalPlanner subject = new AdaptiveRetrievalPlanner(
                analyzer,
                new AdaptiveRetrievalProperties(true, 0.65, 0.95)
        );
        RetrievalPlan current = new RetrievalPlanner().plan(List.of(chunk));

        AdaptiveRetrievalPlanner.ChunkRecommendation recommendation =
                subject.shadow(List.of(chunk), current)
                        .recommendations()
                        .getFirst();

        assertThat(recommendation.queryClass())
                .isEqualTo(AdaptiveRetrievalPlanner.QueryClass.GENERIC);
        assertThat(recommendation.recommendedLanes())
                .containsExactlyInAnyOrder(
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.REFERENCE
                )
                .doesNotContain(RetrievalType.CONCEPT);
    }

    @Test
    void disabledShadowDoesNotAnalyzeQuery() {
        AdaptiveRetrievalPlanner subject = new AdaptiveRetrievalPlanner(
                analyzer,
                AdaptiveRetrievalProperties.defaults()
        );
        QueryChunk chunk = query("capital adequacy ratio");

        assertThat(subject.shadow(
                List.of(chunk),
                new RetrievalPlanner().plan(List.of(chunk))
        ).enabled()).isFalse();
    }

    private QueryChunk query(String text) {
        return new QueryChunk(
                "q1",
                0,
                text,
                text,
                text,
                "en",
                List.of()
        );
    }
}
