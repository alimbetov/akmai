package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.concurrent.atomic.AtomicReference;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdaptiveGraphDreamCoordinatorFailureModelTest {

    private static final String POLICY_FINGERPRINT = "b".repeat(64);

    DreamRuntimeSwitches switches;
    DreamPolicyResolver policies;
    DreamLeaseManager leases;
    DreamLeaseHeartbeat heartbeat;
    DreamCheckpointRepository checkpoints;
    DreamRescanCheckpointRepository rescanCheckpoints;
    DreamRunRepository runs;
    DreamSourceRepository sources;
    DreamCandidateDiscovery discovery;
    DreamMetrics metrics;
    AdaptiveGraphDreamCoordinator coordinator;
    DreamLeaseManager.Authority authority;
    UUID runId;

    @BeforeEach
    void setUp() {
        switches = mock(DreamRuntimeSwitches.class);
        policies = mock(DreamPolicyResolver.class);
        leases = mock(DreamLeaseManager.class);
        heartbeat = mock(DreamLeaseHeartbeat.class);
        checkpoints = mock(DreamCheckpointRepository.class);
        rescanCheckpoints = mock(DreamRescanCheckpointRepository.class);
        runs = mock(DreamRunRepository.class);
        sources = mock(DreamSourceRepository.class);
        discovery = mock(DreamCandidateDiscovery.class);
        metrics = mock(DreamMetrics.class);
        coordinator = new AdaptiveGraphDreamCoordinator(
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
        authority = new DreamLeaseManager.Authority(
                1,
                POLICY_FINGERPRINT,
                "pod-a",
                7
        );
        runId = UUID.randomUUID();
        when(switches.enabled()).thenReturn(true);
    }

    @Test
    void heartbeatLossDuringDiscoveryStopsBeforeFastCheckpointAdvance() {
        AdaptiveGraphProperties.Dream dream = dream(2, 1);
        DreamPolicyResolver.ResolvedDreamPolicy policy = policy(dream);
        DreamSourceRepository.DreamSource source = source();
        AtomicReference<Runnable> onLost = new AtomicReference<>();

        when(policies.resolve()).thenReturn(policy);
        when(leases.tryAcquire(1, POLICY_FINGERPRINT))
                .thenReturn(Optional.of(authority));
        when(heartbeat.start(eq(authority), any())).thenAnswer(invocation -> {
            onLost.set(invocation.getArgument(1));
            return mock(DreamLeaseHeartbeat.Session.class);
        });
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
                        7
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
        )).thenAnswer(invocation -> {
            onLost.get().run();
            DreamBudget budget = invocation.getArgument(4);
            return new DreamCandidateDiscovery.DiscoveryReport(
                    1,
                    0,
                    0,
                    0,
                    0,
                    budget.snapshot()
            );
        });

        AdaptiveGraphDreamCoordinator.RunResult result = coordinator.runOnce();

        assertThat(result)
                .isEqualTo(AdaptiveGraphDreamCoordinator.RunResult.LOST_OWNERSHIP);
        verify(checkpoints, never()).advanceFastWatermark(
                any(), any(), any(), any()
        );
        verify(runs, never()).finishAuthoritative(
                any(),
                any(),
                eq(DreamRunRepository.RunOutcome.SUCCEEDED),
                any(),
                any()
        );
        verify(runs).markLostOwnership(eq(runId), eq(authority), any());
    }

    @Test
    void missingReservedRescanCapacityFailsBeforeLeaseAcquisition() {
        AdaptiveGraphProperties.Dream dream = dream(1, 0);
        when(policies.resolve()).thenReturn(policy(dream));

        AdaptiveGraphDreamCoordinator.RunResult result = coordinator.runOnce();

        assertThat(result)
                .isEqualTo(AdaptiveGraphDreamCoordinator.RunResult.FAILED);
        verify(leases, never()).tryAcquire(anyInt(), any());
    }

    private DreamPolicyResolver.ResolvedDreamPolicy policy(
            AdaptiveGraphProperties.Dream dream
    ) {
        return new DreamPolicyResolver.ResolvedDreamPolicy(
                1,
                "dream-v1",
                POLICY_FINGERPRINT,
                null,
                true,
                dream
        );
    }

    private AdaptiveGraphProperties.Dream dream(
            int maxSources,
            int rescanSources
    ) {
        AdaptiveGraphProperties.Dream dream =
                mock(AdaptiveGraphProperties.Dream.class);
        when(dream.maxSourcesPerRun()).thenReturn(maxSources);
        when(dream.rescanSourcesPerRun()).thenReturn(rescanSources);
        when(dream.maxAnnQueriesPerRun()).thenReturn(20);
        when(dream.maxReverseAnnQueriesPerRun()).thenReturn(20);
        when(dream.maxDbRowsTouchedPerRun()).thenReturn(100L);
        when(dream.maxRunDuration()).thenReturn(Duration.ofMinutes(1));
        when(dream.batchSize()).thenReturn(1);
        return dream;
    }

    private DreamSourceRepository.DreamSource source() {
        return new DreamSourceRepository.DreamSource(
                new ChunkGraphNode(1, "doc-a", 1, "chunk-a"),
                "en",
                new float[] {1f, 0f, 0f},
                Instant.parse("2026-10-09T00:00:00Z"),
                "profile-v1"
        );
    }
}
