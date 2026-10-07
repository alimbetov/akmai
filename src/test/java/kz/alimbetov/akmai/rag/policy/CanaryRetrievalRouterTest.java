package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryOrigin;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import kz.alimbetov.akmai.rag.retrieval.plan.ShadowRetrievalPlanBuilder;
import org.junit.jupiter.api.Test;

class CanaryRetrievalRouterTest {

    private static final double TRAFFIC_PERCENT = 50.0;

    @Test
    void canaryAndControlExecuteDifferentPlansForChangedCandidate() {
        CanaryRetrievalPolicyProvider provider = mock(CanaryRetrievalPolicyProvider.class);
        when(provider.canaryVersion()).thenReturn(Optional.of("retrieval-v2"));
        when(provider.lanes(any())).thenReturn(Optional.of(Set.of(
                RetrievalType.VECTOR,
                RetrievalType.REFERENCE
        )));
        AdaptiveRetrievalPlanner adaptive = mock(AdaptiveRetrievalPlanner.class);
        when(adaptive.classifyPrimary(any()))
                .thenReturn(AdaptiveRetrievalPlanner.QueryClass.GENERIC);
        CanaryRoutingObservationStore store = new CanaryRoutingObservationStore();
        CanaryRetrievalRouter router = router(provider, adaptive, store, true);
        QueryChunk chunk = chunk();
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan control = router.route(
                requestFor(false),
                List.of(chunk),
                baseline,
                baseline
        );
        var controlDecision = store.consumeCurrent().orElseThrow();

        RetrievalPlan canary = router.route(
                requestFor(true),
                List.of(chunk),
                baseline,
                baseline
        );
        var canaryDecision = store.consumeCurrent().orElseThrow();

        assertThat(types(control)).containsExactly(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.CONCEPT,
                RetrievalType.REFERENCE
        );
        assertThat(types(canary)).containsExactly(
                RetrievalType.VECTOR,
                RetrievalType.REFERENCE
        );
        assertThat(controlDecision.cohort())
                .isEqualTo(CanaryRoutingObservationStore.Cohort.CONTROL);
        assertThat(canaryDecision.cohort())
                .isEqualTo(CanaryRoutingObservationStore.Cohort.CANARY);
        assertThat(canaryDecision.candidateWouldChangePlan()).isTrue();
    }

    @Test
    void candidateCannotRemoveMandatorySemanticEvidenceLanes() {
        CanaryRetrievalPolicyProvider provider = mock(CanaryRetrievalPolicyProvider.class);
        when(provider.canaryVersion()).thenReturn(Optional.of("retrieval-v2"));
        when(provider.lanes(any())).thenReturn(Optional.of(Set.of(
                RetrievalType.LEXICAL
        )));
        AdaptiveRetrievalPlanner adaptive = mock(AdaptiveRetrievalPlanner.class);
        when(adaptive.classifyPrimary(any()))
                .thenReturn(AdaptiveRetrievalPlanner.QueryClass.GENERIC);
        CanaryRoutingObservationStore store = new CanaryRoutingObservationStore();
        CanaryRetrievalRouter router = router(provider, adaptive, store, true);
        QueryChunk chunk = chunk();
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan canary = router.route(
                requestFor(true),
                List.of(chunk),
                baseline,
                baseline
        );

        assertThat(types(canary))
                .contains(RetrievalType.VECTOR, RetrievalType.REFERENCE)
                .contains(RetrievalType.LEXICAL)
                .doesNotContain(RetrievalType.CONCEPT);
    }

    @Test
    void masterDisablePreventsCanaryRoutingAndEvidence() {
        CanaryRetrievalPolicyProvider provider = mock(CanaryRetrievalPolicyProvider.class);
        when(provider.canaryVersion()).thenReturn(Optional.of("retrieval-v2"));
        AdaptiveRetrievalPlanner adaptive = mock(AdaptiveRetrievalPlanner.class);
        CanaryRoutingObservationStore store = new CanaryRoutingObservationStore();
        CanaryRetrievalRouter router = router(provider, adaptive, store, false);
        QueryChunk chunk = chunk();
        RetrievalPlan baseline = new RetrievalPlanner().plan(List.of(chunk));

        RetrievalPlan result = router.route(
                requestFor(true),
                List.of(chunk),
                baseline,
                baseline
        );

        assertThat(result.steps()).isEqualTo(baseline.steps());
        assertThat(store.consumeCurrent()).isEmpty();
    }

    private CanaryRetrievalRouter router(
            CanaryRetrievalPolicyProvider provider,
            AdaptiveRetrievalPlanner adaptive,
            CanaryRoutingObservationStore store,
            boolean enabled
    ) {
        return new CanaryRetrievalRouter(
                provider,
                new CanaryEvaluationProperties(
                        TRAFFIC_PERCENT,
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
                new AdaptiveRetrievalProperties(enabled, false, 0.65, 0.95),
                adaptive,
                new ShadowRetrievalPlanBuilder(),
                store
        );
    }

    private QueryChunk chunk() {
        return new QueryChunk(
                "q-1",
                0,
                "How long is the timeout?",
                "How long is the timeout?",
                "How long is the timeout?",
                "en",
                List.of(),
                QueryOrigin.ORIGINAL
        );
    }

    private List<RetrievalType> types(RetrievalPlan plan) {
        return plan.steps().stream().map(RetrievalStep::type).toList();
    }

    private String requestFor(boolean canary) {
        for (long value = 1; value < 100_000; value++) {
            UUID requestId = new UUID(0L, value);
            if (sampled(requestId.toString()) == canary) {
                return requestId.toString();
            }
        }
        throw new IllegalStateException("Cannot find deterministic canary bucket");
    }

    private boolean sampled(String requestId) {
        UUID value = UUID.fromString(requestId);
        long mixed = value.getMostSignificantBits()
                ^ Long.rotateLeft(value.getLeastSignificantBits(), 23);
        int bucket = Math.floorMod(
                (int) (mixed ^ (mixed >>> 32)),
                10_000
        );
        return bucket < Math.round(TRAFFIC_PERCENT * 100.0);
    }
}
