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
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class ApprovedAdaptiveRetrievalPolicyTest {

    @Test
    void productionExecutionPreservesBaselineWithoutApprovedRoute() {
        SemanticQueryAnalyzer analyzer = mock(SemanticQueryAnalyzer.class);
        ApprovedRetrievalPolicyProvider provider =
                mock(ApprovedRetrievalPolicyProvider.class);
        QueryChunk chunk = query("operational requirements");
        when(analyzer.analyze(chunk.semanticText())).thenReturn(genericAnalysis());
        when(provider.lanes(AdaptiveRetrievalPlanner.QueryClass.GENERIC))
                .thenReturn(Optional.empty());
        AdaptiveRetrievalPlanner subject = new AdaptiveRetrievalPlanner(
                analyzer,
                new AdaptiveRetrievalProperties(true, false, 0.65, 0.95),
                provider
        );
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan enforced = subject.enforce(List.of(chunk), baseline);

        assertThat(enforced).isSameAs(baseline);
        assertThat(types(enforced)).containsExactlyInAnyOrder(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.CONCEPT,
                RetrievalType.REFERENCE
        );
    }

    @Test
    void approvedRouteCannotRemoveVectorOrReferenceAuthorityPaths() {
        SemanticQueryAnalyzer analyzer = mock(SemanticQueryAnalyzer.class);
        ApprovedRetrievalPolicyProvider provider =
                mock(ApprovedRetrievalPolicyProvider.class);
        QueryChunk chunk = query("operational requirements");
        when(analyzer.analyze(chunk.semanticText())).thenReturn(genericAnalysis());
        when(provider.lanes(AdaptiveRetrievalPlanner.QueryClass.GENERIC))
                .thenReturn(Optional.of(Set.of(RetrievalType.LEXICAL)));
        AdaptiveRetrievalPlanner subject = new AdaptiveRetrievalPlanner(
                analyzer,
                new AdaptiveRetrievalProperties(true, false, 0.65, 0.95),
                provider
        );
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan enforced = subject.enforce(List.of(chunk), baseline);

        assertThat(types(enforced)).containsExactlyInAnyOrder(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.REFERENCE
        );
        assertThat(types(enforced)).doesNotContain(RetrievalType.CONCEPT);
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
