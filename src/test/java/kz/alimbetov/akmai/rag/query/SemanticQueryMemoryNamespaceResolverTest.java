package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import kz.alimbetov.akmai.rag.policy.CanaryRoutingObservationStore;
import kz.alimbetov.akmai.rag.trace.RagRuntimeAttribution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticQueryMemoryNamespaceResolverTest {

    private RagRuntimeAttribution runtimeAttribution;
    private CanaryRoutingObservationStore observationStore;
    private SemanticQueryMemoryNamespaceResolver resolver;

    @BeforeEach
    void setUp() {
        runtimeAttribution = mock(RagRuntimeAttribution.class);
        observationStore = new CanaryRoutingObservationStore();
        resolver = new SemanticQueryMemoryNamespaceResolver(
                runtimeAttribution,
                observationStore
        );
        when(runtimeAttribution.snapshot()).thenReturn(snapshot());
    }

    @AfterEach
    void tearDown() {
        observationStore.clearCurrent();
    }

    @Test
    void baselineNamespaceUsesRuntimePolicyIdentity() {
        assertThat(resolver.resolve()).contains(new SemanticQueryMemoryNamespace(
                "embedding-v1",
                "retrieval-approved",
                "learning-v2",
                "grounding-v3"
        ));
    }

    @Test
    void controlCohortKeepsApprovedRetrievalIdentity() {
        observationStore.recordCurrent(decision(
                "retrieval-candidate",
                CanaryRoutingObservationStore.Cohort.CONTROL
        ));

        assertThat(resolver.resolve())
                .get()
                .extracting(SemanticQueryMemoryNamespace::retrievalPolicyVersion)
                .isEqualTo("retrieval-approved");
        assertThat(observationStore.consumeCurrent())
                .get()
                .extracting(CanaryRoutingObservationStore.Decision::policyVersion)
                .isEqualTo("retrieval-candidate");
    }

    @Test
    void canaryCohortUsesCandidateWithoutConsumingDecision() {
        observationStore.recordCurrent(decision(
                "retrieval-candidate",
                CanaryRoutingObservationStore.Cohort.CANARY
        ));

        assertThat(resolver.resolve())
                .get()
                .extracting(SemanticQueryMemoryNamespace::retrievalPolicyVersion)
                .isEqualTo("retrieval-candidate");
        assertThat(observationStore.consumeCurrent())
                .get()
                .extracting(CanaryRoutingObservationStore.Decision::policyVersion)
                .isEqualTo("retrieval-candidate");
    }

    private RagRuntimeAttribution.Snapshot snapshot() {
        return new RagRuntimeAttribution.Snapshot(
                "git-sha",
                "corpus-v1",
                "embedding-v1",
                "retrieval-approved",
                "learning-v2",
                "grounding-v3",
                "test"
        );
    }

    private CanaryRoutingObservationStore.Decision decision(
            String policyVersion,
            CanaryRoutingObservationStore.Cohort cohort
    ) {
        return new CanaryRoutingObservationStore.Decision(
                policyVersion,
                cohort,
                "FACTUAL",
                true,
                Instant.parse("2026-10-07T00:00:00Z")
        );
    }
}
