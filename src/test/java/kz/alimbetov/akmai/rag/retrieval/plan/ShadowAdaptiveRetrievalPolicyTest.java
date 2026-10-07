package kz.alimbetov.akmai.rag.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.policy.ApprovedRetrievalPolicyProvider;
import kz.alimbetov.akmai.rag.policy.ShadowRetrievalPolicyProvider;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class ShadowAdaptiveRetrievalPolicyTest {

    @Test
    void approvedPolicyControlsExecutionWhileShadowPolicyOnlyControlsObservation() {
        SemanticQueryAnalyzer analyzer = mock(SemanticQueryAnalyzer.class);
        ApprovedRetrievalPolicyProvider approved =
                mock(ApprovedRetrievalPolicyProvider.class);
        ShadowRetrievalPolicyProvider shadow =
                mock(ShadowRetrievalPolicyProvider.class);
        QueryChunk chunk = query("operational requirements");
        when(analyzer.analyze(chunk.semanticText())).thenReturn(genericAnalysis());
        when(approved.lanes(AdaptiveRetrievalPlanner.QueryClass.GENERIC))
                .thenReturn(Optional.of(Set.of(
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.REFERENCE
                )));
        when(shadow.lanes(AdaptiveRetrievalPlanner.QueryClass.GENERIC))
                .thenReturn(Optional.of(Set.of(
                        RetrievalType.VECTOR,
                        RetrievalType.REFERENCE
                )));

        AdaptiveRetrievalPlanner subject = new AdaptiveRetrievalPlanner(
                analyzer,
                new AdaptiveRetrievalProperties(true, true, 0.65, 0.95),
                approved,
                shadow
        );
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan enforced = subject.enforce(List.of(chunk), baseline);
        AdaptiveRetrievalPlanner.ShadowPlanReport observed = subject.shadow(
                List.of(chunk),
                enforced
        );

        assertThat(types(enforced)).containsExactlyInAnyOrder(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.REFERENCE
        );
        assertThat(observed.enabled()).isTrue();
        assertThat(observed.recommendations()).hasSize(1);
        assertThat(observed.recommendations().getFirst().recommendedLanes())
                .containsExactlyInAnyOrder(
                        RetrievalType.VECTOR,
                        RetrievalType.REFERENCE
                );
        assertThat(types(enforced)).contains(RetrievalType.LEXICAL);
    }

    private Set<RetrievalType> types(RetrievalPlan plan) {
        return plan.steps().stream()
                .map(RetrievalStep::type)
                .collect(Collectors.toSet());
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

    private SemanticQueryAnalysis genericAnalysis() {
        return new SemanticQueryAnalysis(
                "en",
                "en",
                0.95,
                List.of(),
                List.of()
        );
    }
}
