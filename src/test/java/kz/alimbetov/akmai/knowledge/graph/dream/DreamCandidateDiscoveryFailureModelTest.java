package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.SemanticNeighborSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DreamCandidateDiscoveryFailureModelTest {

    private static final String POLICY_FINGERPRINT = "a".repeat(64);

    SemanticNeighborSearchRepository neighbors;
    DreamReciprocalNeighborVerifier reciprocalVerifier;
    DreamConfidenceCalculator confidenceCalculator;
    DreamCandidateRepository candidates;
    SemanticGraphPriorWriter priorWriter;
    DreamMetrics metrics;
    DreamCandidateDiscovery discovery;
    AdaptiveGraphProperties.Dream dream;
    DreamPolicyResolver.ResolvedDreamPolicy policy;
    DreamLeaseManager.Authority authority;
    DreamSourceRepository.DreamSource source;
    SemanticNeighborSearchRepository.SemanticNeighbor neighbor;
    DreamPair pair;

    @BeforeEach
    void setUp() {
        neighbors = mock(SemanticNeighborSearchRepository.class);
        reciprocalVerifier = mock(DreamReciprocalNeighborVerifier.class);
        confidenceCalculator = mock(DreamConfidenceCalculator.class);
        candidates = mock(DreamCandidateRepository.class);
        priorWriter = mock(SemanticGraphPriorWriter.class);
        metrics = mock(DreamMetrics.class);
        discovery = new DreamCandidateDiscovery(
                neighbors,
                reciprocalVerifier,
                confidenceCalculator,
                candidates,
                priorWriter,
                metrics
        );

        dream = mock(AdaptiveGraphProperties.Dream.class);
        when(dream.topK()).thenReturn(32);
        when(dream.candidateThreshold()).thenReturn(0.86);
        when(dream.activationThreshold()).thenReturn(0.94);
        when(dream.retentionThreshold()).thenReturn(0.90);
        when(dream.maxNewEdgesPerChunk()).thenReturn(3);
        when(dream.queryTimeout()).thenReturn(Duration.ofSeconds(5));

        policy = new DreamPolicyResolver.ResolvedDreamPolicy(
                1,
                "dream-v1",
                POLICY_FINGERPRINT,
                null,
                true,
                dream
        );
        authority = new DreamLeaseManager.Authority(
                1,
                POLICY_FINGERPRINT,
                "pod-a",
                7
        );

        ChunkGraphNode sourceNode =
                new ChunkGraphNode(1, "doc-a", 1, "chunk-a");
        ChunkGraphNode targetNode =
                new ChunkGraphNode(1, "doc-b", 1, "chunk-b");
        source = new DreamSourceRepository.DreamSource(
                sourceNode,
                "en",
                new float[] {1f, 0f, 0f},
                Instant.parse("2026-10-09T00:00:00Z"),
                "profile-v1"
        );
        neighbor = new SemanticNeighborSearchRepository.SemanticNeighbor(
                targetNode,
                "en",
                0.92
        );
        pair = DreamPair.of(sourceNode, targetNode);
    }

    @Test
    void existingActivePairUsesRetentionThresholdAndRefreshesPrior() {
        when(neighbors.search(
                any(float[].class),
                eq("en"),
                eq(1L),
                eq(33),
                eq(0.86),
                eq(true),
                eq(Duration.ofSeconds(5))
        )).thenReturn(List.of(neighbor));
        when(candidates.findActivePairsForSource(
                1,
                POLICY_FINGERPRINT,
                source.node()
        )).thenReturn(List.of(pair));
        when(reciprocalVerifier.verify(
                any(), eq(source), eq(neighbor), eq(1), eq(policy), any()
        )).thenReturn(new DreamReciprocalNeighborVerifier.Verification(
                true,
                true,
                0.92,
                0.92,
                1,
                1,
                false
        ));
        when(confidenceCalculator.calculate(
                anyDouble(), anyDouble(), anyInt(), anyInt(), anyInt(), anyBoolean()
        )).thenReturn(0.92);
        when(priorWriter.applyCandidate(
                eq(authority),
                eq(pair),
                eq(0.92),
                any()
        )).thenReturn(SemanticGraphPriorWriter.ApplyResult.REFRESHED);

        DreamCandidateDiscovery.DiscoveryReport report = discovery.discover(
                List.of(source),
                UUID.randomUUID(),
                authority,
                policy,
                budget(20),
                "rescan"
        );

        ArgumentCaptor<DreamCandidateRepository.Observation> observation =
                ArgumentCaptor.forClass(DreamCandidateRepository.Observation.class);
        verify(candidates).observe(eq(authority), observation.capture());
        assertThat(observation.getValue().state())
                .isEqualTo(DreamCandidateRepository.CandidateState.ACTIVE);
        assertThat(report.activatedCandidates()).isZero();
        assertThat(report.appliedSemanticPriors()).isEqualTo(1);
        assertThat(report.budget().dbRowsTouched()).isEqualTo(3);
    }

    @Test
    void activePairMissingFromCurrentForwardTopKBecomesStale() {
        when(neighbors.search(
                any(float[].class),
                any(),
                anyLong(),
                anyInt(),
                anyDouble(),
                anyBoolean(),
                any()
        )).thenReturn(List.of());
        when(candidates.findActivePairsForSource(
                1,
                POLICY_FINGERPRINT,
                source.node()
        )).thenReturn(List.of(pair));
        when(candidates.markStaleIfActive(
                eq(authority),
                eq(pair),
                any(),
                eq("not-in-forward-topk"),
                any()
        )).thenReturn(true);

        DreamCandidateDiscovery.DiscoveryReport report = discovery.discover(
                List.of(source),
                UUID.randomUUID(),
                authority,
                policy,
                budget(20),
                "rescan"
        );

        assertThat(report.persistedCandidates()).isEqualTo(1);
        assertThat(report.budget().dbRowsTouched()).isEqualTo(1);
        verify(candidates, never()).observe(any(), any());
        verify(priorWriter, never()).applyCandidate(
                any(), any(), anyDouble(), any()
        );
    }

    @Test
    void lifecycleIneligibleVerificationRetiresExistingActivePair() {
        when(neighbors.search(
                any(float[].class),
                any(),
                anyLong(),
                anyInt(),
                anyDouble(),
                anyBoolean(),
                any()
        )).thenReturn(List.of(neighbor));
        when(candidates.findActivePairsForSource(
                1,
                POLICY_FINGERPRINT,
                source.node()
        )).thenReturn(List.of(pair));
        when(reciprocalVerifier.verify(
                any(), eq(source), eq(neighbor), eq(1), eq(policy), any()
        )).thenReturn(new DreamReciprocalNeighborVerifier.Verification(
                false,
                false,
                0.92,
                0.0,
                1,
                33,
                false
        ));
        when(candidates.markStaleIfActive(
                eq(authority),
                eq(pair),
                any(),
                eq("lifecycle-ineligible"),
                any()
        )).thenReturn(true);

        DreamCandidateDiscovery.DiscoveryReport report = discovery.discover(
                List.of(source),
                UUID.randomUUID(),
                authority,
                policy,
                budget(20),
                "fast"
        );

        assertThat(report.persistedCandidates()).isEqualTo(1);
        verify(candidates, never()).observe(any(), any());
        verify(priorWriter, never()).applyCandidate(
                any(), any(), anyDouble(), any()
        );
    }

    @Test
    void dbRowBudgetIsCheckedBeforeStaleCandidateMutation() {
        when(neighbors.search(
                any(float[].class),
                any(),
                anyLong(),
                anyInt(),
                anyDouble(),
                anyBoolean(),
                any()
        )).thenReturn(List.of());
        when(candidates.findActivePairsForSource(
                1,
                POLICY_FINGERPRINT,
                source.node()
        )).thenReturn(List.of(pair));

        DreamBudget budget = budget(1);
        budget.addDbRows(1);

        assertThatThrownBy(() -> discovery.discover(
                List.of(source),
                UUID.randomUUID(),
                authority,
                policy,
                budget,
                "rescan"
        )).isInstanceOf(DreamBudget.BudgetExhaustedException.class)
                .satisfies(error -> assertThat(
                        ((DreamBudget.BudgetExhaustedException) error).reason()
                ).isEqualTo(DreamBudget.StopReason.MAX_DB_ROWS));

        verify(candidates, never()).markStaleIfActive(
                any(), any(), any(), any(), any()
        );
    }

    private DreamBudget budget(long dbRows) {
        return new DreamBudget(
                10,
                20,
                20,
                dbRows,
                Duration.ofMinutes(1)
        );
    }
}
