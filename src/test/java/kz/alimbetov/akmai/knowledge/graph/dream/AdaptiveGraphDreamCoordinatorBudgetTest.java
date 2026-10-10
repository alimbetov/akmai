package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import org.junit.jupiter.api.Test;

class AdaptiveGraphDreamCoordinatorBudgetTest {

    private static final String POLICY_FINGERPRINT = "c".repeat(64);

    @Test
    void budgetExhaustionFinalizesPartialRunAndStopsFurtherLanes() {
        DreamRuntimeSwitches switches = mock(DreamRuntimeSwitches.class);
        DreamPolicyResolver policies = mock(DreamPolicyResolver.class);
        DreamLeaseManager leases = mock(DreamLeaseManager.class);
        DreamLeaseHeartbeat heartbeat = mock(DreamLeaseHeartbeat.class);
        DreamCheckpointRepository checkpoints = mock(DreamCheckpointRepository.class);
        DreamRescanCheckpointRepository rescanCheckpoints =
                mock(DreamRescanCheckpointRepository.class);
        DreamRunRepository runs = mock(DreamRunRepository.class);
        DreamSourceRepository sources = mock(DreamSourceRepository.class);
        DreamCandidateDiscovery discovery = mock(DreamCandidateDiscovery.class);
        DreamMetrics metrics = mock(DreamMetrics.class);

        AdaptiveGraphDreamCoordinator coordinator = new AdaptiveGraphDreamCoordinator(
                switches,
                policies,
                leases,
                heartbeat,
                checkpoints,
                rescanCheckpoints,
                runs,
                sources,
                discovery,
                metrics
        );

        AdaptiveGraphProperties.Dream dream = mock(AdaptiveGraphProperties.Dream.class);
        when(dream.maxSourcesPerRun()).thenReturn(2);
        when(dream.rescanSourcesPerRun()).thenReturn(1);
        when(dream.maxAnnQueriesPerRun()).thenReturn(1);
        when(dream.maxReverseAnnQueriesPerRun()).thenReturn(1);
        when(dream.maxDbRowsTouchedPerRun()).thenReturn(10L);
        when(dream.maxRunDuration()).thenReturn(Duration.ofMinutes(1));
        when(dream.batchSize()).thenReturn(1);

        DreamPolicyResolver.ResolvedDreamPolicy policy =
                new DreamPolicyResolver.ResolvedDreamPolicy(
                        1,
                        "dream-v1",
                        POLICY_FINGERPRINT,
                        null,
                        true,
                        dream
                );
        DreamLeaseManager.Authority authority = new DreamLeaseManager.Authority(
                1,
                POLICY_FINGERPRINT,
                "pod-a",
                11
        );
        UUID runId = UUID.randomUUID();
        DreamLeaseHeartbeat.Session session = mock(DreamLeaseHeartbeat.Session.class);
        DreamSourceRepository.DreamSource source = new DreamSourceRepository.DreamSource(
                new ChunkGraphNode(1, "doc-a", 1, "chunk-a"),
                "en",
                new float[] {1f, 0f, 0f},
                Instant.parse("2026-10-10T00:00:00Z"),
                "profile-v1"
        );

        when(switches.enabled()).thenReturn(true);
        when(policies.resolve()).thenReturn(policy);
        when(leases.tryAcquire(1, POLICY_FINGERPRINT))
                .thenReturn(Optional.of(authority));
        when(heartbeat.start(eq(authority), any())).thenReturn(session);
        when(runs.start(authority, policy)).thenReturn(runId);
        when(checkpoints.find(1, POLICY_FINGERPRINT)).thenReturn(Optional.of(
                new DreamCheckpointRepository.Checkpoint(
                        1,
                        POLICY_FINGERPRINT,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        11
                )
        ));
        when(sources.findFastAfter(null, 1)).thenReturn(List.of(source));
        when(discovery.discover(
                eq(List.of(source)),
                eq(runId),
                eq(authority),
                eq(policy),
                any(),
                eq("fast")
        )).thenThrow(new DreamBudget.BudgetExhaustedException(
                DreamBudget.StopReason.MAX_ANN_QUERIES
        ));

        AdaptiveGraphDreamCoordinator.RunResult result = coordinator.runOnce();

        assertThat(result)
                .isEqualTo(AdaptiveGraphDreamCoordinator.RunResult.PARTIAL_BUDGET);
        verify(metrics).budgetStop(DreamBudget.StopReason.MAX_ANN_QUERIES);
        verify(runs).finishAuthoritative(
                eq(runId),
                eq(authority),
                eq(DreamRunRepository.RunOutcome.PARTIAL_BUDGET),
                any(),
                eq("MAX_ANN_QUERIES")
        );
        verify(sources, never()).findRescanAfter(any(), any(Integer.class));
        verify(discovery).clearRun(runId);
        verify(session).close();
        verify(leases).release(authority);
    }
}
