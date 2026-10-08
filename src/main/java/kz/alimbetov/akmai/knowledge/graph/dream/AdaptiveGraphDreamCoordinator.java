package kz.alimbetov.akmai.knowledge.graph.dream;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single-owner DREAM-1..4B coordinator. Work is intentionally sequential in v1
 * until target-hardware measurements justify bounded parallelism.
 */
@Component
public class AdaptiveGraphDreamCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdaptiveGraphDreamCoordinator.class);

    private final DreamRuntimeSwitches switches;
    private final DreamPolicyResolver policies;
    private final DreamLeaseManager leases;
    private final DreamLeaseHeartbeat heartbeat;
    private final DreamCheckpointRepository checkpoints;
    private final DreamRunRepository runs;
    private final DreamSourceRepository sources;
    private final DreamCandidateDiscovery discovery;
    private final DreamMetrics metrics;
    private final AtomicBoolean localRunActive = new AtomicBoolean();

    public AdaptiveGraphDreamCoordinator(
            DreamRuntimeSwitches switches,
            DreamPolicyResolver policies,
            DreamLeaseManager leases,
            DreamLeaseHeartbeat heartbeat,
            DreamCheckpointRepository checkpoints,
            DreamRunRepository runs,
            DreamSourceRepository sources,
            DreamCandidateDiscovery discovery,
            DreamMetrics metrics
    ) {
        this.switches = switches;
        this.policies = policies;
        this.leases = leases;
        this.heartbeat = heartbeat;
        this.checkpoints = checkpoints;
        this.runs = runs;
        this.sources = sources;
        this.discovery = discovery;
        this.metrics = metrics;
    }

    public RunResult runOnce() {
        if (!switches.enabled()) {
            return RunResult.DISABLED;
        }
        if (!localRunActive.compareAndSet(false, true)) {
            return RunResult.LOCAL_OVERLAP_SKIPPED;
        }

        Instant started = Instant.now();
        DreamLeaseManager.Authority authority = null;
        UUID runId = null;
        DreamBudget budget = null;
        DreamLeaseHeartbeat.Session heartbeatSession = null;
        AtomicBoolean authorityLost = new AtomicBoolean();
        try {
            DreamPolicyResolver.ResolvedDreamPolicy policy = policies.resolve();
            Optional<DreamLeaseManager.Authority> acquired = leases.tryAcquire(
                    policy.graphVersion(), policy.fingerprint()
            );
            if (acquired.isEmpty()) {
                metrics.lease("standby");
                return RunResult.STANDBY;
            }
            authority = acquired.get();
            metrics.lease("acquired");

            AdaptiveGraphProperties.Dream configuration = policy.dream();
            budget = new DreamBudget(
                    configuration.maxSourcesPerRun(),
                    configuration.maxAnnQueriesPerRun(),
                    configuration.maxReverseAnnQueriesPerRun(),
                    configuration.maxDbRowsTouchedPerRun(),
                    configuration.maxRunDuration()
            );
            DreamBudget activeBudget = budget;
            DreamLeaseManager.Authority activeAuthority = authority;
            heartbeatSession = heartbeat.start(
                    activeAuthority,
                    () -> authorityLost.set(true)
            );

            checkpoints.initialize(authority);
            runId = runs.start(authority, policy);
            DreamCheckpointRepository.Checkpoint checkpoint = checkpoints.find(
                    policy.graphVersion(), policy.fingerprint()
            ).orElseThrow(() -> new IllegalStateException(
                    "Dream checkpoint was not initialized"
            ));
            DreamCheckpointRepository.Watermark cursor = checkpoint
                    .fastWatermark()
                    .orElse(null);

            boolean complete = false;
            while (!complete) {
                requireAuthority(authorityLost, activeAuthority, activeBudget);
                List<DreamSourceRepository.DreamSource> batch =
                        sources.findFastAfter(cursor, configuration.batchSize());
                if (batch.isEmpty()) {
                    complete = true;
                    break;
                }

                for (DreamSourceRepository.DreamSource source : batch) {
                    requireAuthority(
                            authorityLost,
                            activeAuthority,
                            activeBudget
                    );
                    discovery.discover(
                            List.of(source),
                            runId,
                            activeAuthority,
                            policy,
                            activeBudget
                    );
                    DreamCheckpointRepository.Watermark next = source.watermark();
                    checkpoints.advanceFastWatermark(
                            activeAuthority,
                            cursor,
                            next,
                            runId
                    );
                    cursor = next;
                }
                complete = batch.size() < configuration.batchSize();
            }

            runs.finishAuthoritative(
                    runId,
                    authority,
                    DreamRunRepository.RunOutcome.SUCCEEDED,
                    budget.snapshot(),
                    "FAST_LANE_COMPLETE"
            );
            metrics.run("succeeded", Duration.between(started, Instant.now()));
            return RunResult.SUCCEEDED;
        } catch (DreamBudget.BudgetExhaustedException exhausted) {
            if (budget != null) {
                metrics.budgetStop(exhausted.reason());
            }
            return finishPartial(
                    started,
                    authority,
                    runId,
                    budget,
                    exhausted.reason().name()
            );
        } catch (DreamLeaseManager.LostDreamAuthorityException lost) {
            if (runId != null && authority != null && budget != null) {
                runs.markLostOwnership(runId, authority, budget.snapshot());
            }
            metrics.lease("lost");
            metrics.run("lost_ownership", Duration.between(started, Instant.now()));
            return RunResult.LOST_OWNERSHIP;
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "dream_run event=failed errorType={}",
                    failure.getClass().getSimpleName(),
                    failure
            );
            if (runId != null && authority != null && budget != null) {
                try {
                    if (leases.isOwned(authority)) {
                        runs.finishAuthoritative(
                                runId,
                                authority,
                                DreamRunRepository.RunOutcome.FAILED,
                                budget.snapshot(),
                                failure.getClass().getSimpleName()
                        );
                    } else {
                        runs.markLostOwnership(runId, authority, budget.snapshot());
                    }
                } catch (RuntimeException auditFailure) {
                    LOGGER.warn(
                            "dream_run event=finalization_failed errorType={}",
                            auditFailure.getClass().getSimpleName()
                    );
                }
            }
            metrics.run("failed", Duration.between(started, Instant.now()));
            return RunResult.FAILED;
        } finally {
            if (heartbeatSession != null) {
                heartbeatSession.close();
            }
            if (authority != null) {
                try {
                    leases.release(authority);
                } catch (RuntimeException ignored) {
                    LOGGER.debug("dream_lease event=release_failed");
                }
            }
            localRunActive.set(false);
        }
    }

    private RunResult finishPartial(
            Instant started,
            DreamLeaseManager.Authority authority,
            UUID runId,
            DreamBudget budget,
            String reason
    ) {
        if (authority == null || runId == null || budget == null) {
            metrics.run("failed", Duration.between(started, Instant.now()));
            return RunResult.FAILED;
        }
        try {
            runs.finishAuthoritative(
                    runId,
                    authority,
                    DreamRunRepository.RunOutcome.PARTIAL_BUDGET,
                    budget.snapshot(),
                    reason
            );
            metrics.run("partial_budget", Duration.between(started, Instant.now()));
            return RunResult.PARTIAL_BUDGET;
        } catch (DreamLeaseManager.LostDreamAuthorityException lost) {
            runs.markLostOwnership(runId, authority, budget.snapshot());
            metrics.run("lost_ownership", Duration.between(started, Instant.now()));
            return RunResult.LOST_OWNERSHIP;
        }
    }

    private void requireAuthority(
            AtomicBoolean authorityLost,
            DreamLeaseManager.Authority authority,
            DreamBudget budget
    ) {
        budget.requireTime();
        if (authorityLost.get() || !leases.isOwned(authority)) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream authority was lost during run"
            );
        }
    }

    public enum RunResult {
        DISABLED,
        STANDBY,
        LOCAL_OVERLAP_SKIPPED,
        SUCCEEDED,
        PARTIAL_BUDGET,
        LOST_OWNERSHIP,
        FAILED
    }
}
