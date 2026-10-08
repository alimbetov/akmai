package kz.alimbetov.akmai.rag.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.policy.ApprovedRetrievalPolicyProvider;
import kz.alimbetov.akmai.rag.query.AdvancedRetrievalProperties;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryOrigin;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class RetrievalPlannerRoutingTest {

    @Test
    void identifierOnlyQueryUsesIdentifierLaneOnly() {
        RetrievalPlan plan = new RetrievalPlanner().plan(List.of(identifierOnly()));

        assertThat(plan.steps())
                .extracting(RetrievalStep::type)
                .containsExactly(RetrievalType.IDENTIFIER);
    }

    @Test
    void missingApprovedPolicyFallsBackToFullBaseline() {
        SemanticQueryAnalyzer analyzer = mock(SemanticQueryAnalyzer.class);
        ApprovedRetrievalPolicyProvider provider = mock(ApprovedRetrievalPolicyProvider.class);
        when(provider.lanes(any())).thenReturn(Optional.empty());
        AdaptiveRetrievalPlanner adaptive = new AdaptiveRetrievalPlanner(
                analyzer,
                new AdaptiveRetrievalProperties(true, false, 0.65, 0.95),
                provider
        );
        QueryChunk chunk = semantic("q1", "payment deadline");
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan routed = adaptive.enforce(List.of(chunk), baseline);

        assertThat(routed).isSameAs(baseline);
        assertThat(types(routed)).containsExactlyInAnyOrder(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.CONCEPT,
                RetrievalType.REFERENCE
        );
    }

    @Test
    void unsafeApprovedPolicyCannotRemoveMandatoryIdentifierVectorOrReferenceLanes() {
        SemanticQueryAnalyzer analyzer = mock(SemanticQueryAnalyzer.class);
        ApprovedRetrievalPolicyProvider provider = mock(ApprovedRetrievalPolicyProvider.class);
        when(provider.lanes(any())).thenReturn(Optional.of(Set.of(RetrievalType.LEXICAL)));
        AdaptiveRetrievalPlanner adaptive = new AdaptiveRetrievalPlanner(
                analyzer,
                new AdaptiveRetrievalProperties(true, false, 0.65, 0.95),
                provider
        );
        QueryChunk chunk = identifierSemantic();
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan routed = adaptive.enforce(List.of(chunk), baseline);

        assertThat(types(routed))
                .contains(
                        RetrievalType.IDENTIFIER,
                        RetrievalType.VECTOR,
                        RetrievalType.REFERENCE,
                        RetrievalType.LEXICAL
                )
                .doesNotContain(RetrievalType.CONCEPT);
    }

    @Test
    void hydeIsAddedOnlyForOriginalPrimarySemanticQueryWithVectorLane() {
        RetrievalPlanner planner = new RetrievalPlanner(null, advanced(true));
        QueryChunk chunk = semantic("q1", "payment deadline");

        RetrievalPlan plan = planner.plan(List.of(chunk));

        assertThat(plan.steps())
                .extracting(RetrievalStep::type)
                .containsExactly(
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.CONCEPT,
                        RetrievalType.REFERENCE,
                        RetrievalType.HYDE_VECTOR
                );
    }

    @Test
    void hydeIsNotAddedForIdentifierQuery() {
        RetrievalPlanner planner = new RetrievalPlanner(null, advanced(true));

        RetrievalPlan plan = planner.plan(List.of(identifierSemantic()));

        assertThat(types(plan)).doesNotContain(RetrievalType.HYDE_VECTOR);
    }

    @Test
    void hydeIsNotAddedForMultiQueryVariant() {
        RetrievalPlanner planner = new RetrievalPlanner(null, advanced(true));
        QueryChunk chunk = new QueryChunk(
                "mq-1",
                0,
                "payment deadline",
                "payment deadline",
                "payment deadline",
                "en",
                List.of(),
                QueryOrigin.MULTI_QUERY
        );

        RetrievalPlan plan = planner.plan(List.of(chunk));

        assertThat(types(plan)).doesNotContain(RetrievalType.HYDE_VECTOR);
    }

    private Set<RetrievalType> types(RetrievalPlan plan) {
        return plan.steps().stream()
                .map(RetrievalStep::type)
                .collect(java.util.stream.Collectors.toSet());
    }

    private QueryChunk semantic(String id, String text) {
        return new QueryChunk(id, 0, text, text, text, "en", List.of());
    }

    private QueryChunk identifierOnly() {
        return new QueryChunk(
                "id-only",
                0,
                "KZ-2026-001847",
                "KZ-2026-001847",
                "",
                "en",
                List.of(identifier())
        );
    }

    private QueryChunk identifierSemantic() {
        return new QueryChunk(
                "id-semantic",
                0,
                "payment deadline KZ-2026-001847",
                "payment deadline KZ-2026-001847",
                "payment deadline",
                "en",
                List.of(identifier())
        );
    }

    private DetectedIdentifier identifier() {
        return new DetectedIdentifier(
                IdentifierType.CONTRACT_NUMBER,
                "KZ-2026-001847",
                "KZ2026001847",
                "contract KZ-2026-001847"
        );
    }

    private AdvancedRetrievalProperties advanced(boolean hydeEnabled) {
        return new AdvancedRetrievalProperties(
                false,
                3,
                16,
                hydeEnabled,
                false,
                2048,
                2,
                0.86,
                1800,
                Duration.ofSeconds(2),
                false,
                "",
                Duration.ofSeconds(2)
        );
    }
}
