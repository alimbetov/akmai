package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.ShadowRetrievalPlanBuilder;
import org.junit.jupiter.api.Test;

class CanaryRetrievalRouterFallbackTest {

    @Test
    void missingCanaryPolicyFallsBackToProductionWithoutObservation() {
        CanaryRetrievalPolicyProvider provider = mock(CanaryRetrievalPolicyProvider.class);
        when(provider.canaryVersion()).thenReturn(Optional.empty());
        CanaryRoutingObservationStore store = new CanaryRoutingObservationStore();
        CanaryRetrievalRouter router = router(provider, store);
        QueryChunk chunk = chunk();
        RetrievalPlan production = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan routed = router.route("request-1", List.of(chunk), production, production);

        assertThat(routed).isSameAs(production);
        assertThat(store.consumeCurrent()).isEmpty();
    }

    @Test
    void unchangedCanaryCandidateFallsBackToProductionWithoutCohortDecision() {
        CanaryRetrievalPolicyProvider provider = mock(CanaryRetrievalPolicyProvider.class);
        when(provider.canaryVersion()).thenReturn(Optional.of("retrieval-v2"));
        when(provider.lanes(any())).thenReturn(Optional.of(Set.of(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.CONCEPT,
                RetrievalType.REFERENCE
        )));
        CanaryRoutingObservationStore store = new CanaryRoutingObservationStore();
        CanaryRetrievalRouter router = router(provider, store);
        QueryChunk chunk = chunk();
        RetrievalPlan production = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan routed = router.route("request-2", List.of(chunk), production, production);

        assertThat(routed.steps()).isEqualTo(production.steps());
        assertThat(store.consumeCurrent()).isEmpty();
    }

    private CanaryRetrievalRouter router(
            CanaryRetrievalPolicyProvider provider,
            CanaryRoutingObservationStore store
    ) {
        AdaptiveRetrievalPlanner adaptive = mock(AdaptiveRetrievalPlanner.class);
        when(adaptive.classifyPrimary(any()))
                .thenReturn(AdaptiveRetrievalPlanner.QueryClass.GENERIC);
        return new CanaryRetrievalRouter(
                provider,
                new CanaryEvaluationProperties(
                        50.0,
                        10,
                        20,
                        3,
                        0.03,
                        0.01,
                        0.01,
                        0.05,
                        0.20,
                        100
                ),
                new AdaptiveRetrievalProperties(true, false, 0.65, 0.95),
                adaptive,
                new ShadowRetrievalPlanBuilder(),
                store
        );
    }

    private QueryChunk chunk() {
        return new QueryChunk(
                "q-1",
                0,
                "payment deadline",
                "payment deadline",
                "payment deadline",
                "en",
                List.of()
        );
    }
}
