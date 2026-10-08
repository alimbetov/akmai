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
    private final DreamRescanCheckpointRepository rescanCheckpoints;
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
            DreamRescanCheckpointRepository rescanCheckpoints,
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
        this.rescanCheckpoints = rescanCheckpoints;
        this.runs = runs;
        this.sources = sources;
        this.discovery = discovery;
        this.metrics = metrics;
    }

    public RunResult runOnce() {
        if (!switches.enabled()) {
            return RunResult.DISABLED;
        }
        if (switches.applyEnabled()) {
            LOGGER.warn("dream_run event=apply_not_implemented");
            return RunResult.APPLY_MODE_UNSUPPORTED;
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

            int rescanReservation = Math.min(
                    configuration.rescanSourcesPerRun(),
                    Math.max(0, configuration.maxSourcesPerRun() - 1)
            );
            int fastLimit = configuration.maxSourcesPerRun()
                    - rescanReservation;

            boolean fastComplete = processFastLane(
                    authorityLost,
                    activeAuthority,
                    runId,
                    policy,
                    activeBudget,
                    checkpoint.fastWatermark().orElse(null),
                    fastLimit
            );
            processRescanLane(
                    authorityLost,
                    activeAuthority,
                    runId,
                    policy,
                    activeBudget,
                    checkpoint.rescanCursor(),
                    rescanReservation
            );

            if (!fastComplete) {
                return finishPartial(
                        started,
                        authority,
                        runId,
                        budget,
                        "FAST_LANE_RESERVED_RESCAN"
                );
            }

            runs.finishAuthoritative(
                    runId,
                    authority,
                    DreamRunRepository.RunOutcome.SUCCEEDED,
                    budget.snapshot(),
                    "FAST_AND_RESCAN_COMPLETE"
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
            metrics.run(
                    "lost_ownership",
                    Duration.between(started, Instant.now())
            );
            return RunResult.LOST_OWNERSHIP;
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "dream_run event=failed errorType={}",
                    failure.getClass().getSimpleName(),
                    failure
            );
            if (runId != null && authority != null && budget != null) {
                finalizeFailure(runId, authority, budget, failure);
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

    private boolean processFastLane(
            AtomicBoolean authorityLost,
            DreamLeaseManager.Authority authority,
            UUID runId,
            DreamPolicyResolver.ResolvedDreamPolicy policy,
            DreamBudget budget,
            DreamCheckpointRepository.Watermark initialCursor,
            int fastLimit
    ) {
        DreamCheckpointRepository.Watermark cursor = initialCursor;
        int processed = 0;
        while (processed < fastLimit) {
            requireContinue(authorityLost, budget);
            int queryLimit = Math.min(
                    policy.dream().batchSize(),
                    fastLimit - processed
            );
            List<DreamSourceRepository.DreamSource> batch =
                    sources.findFastAfter(cursor, queryLimit);
            if (batch.isEmpty()) {
                return true;
            }
            for (DreamSourceRepository.DreamSource source : batch) {
                requireContinue(authorityLost, budget);
                discovery.discover(
                        List.of(source),
                        runId,
                        authority,
                        policy,
                        budget,
                        "fast"
                );
                DreamCheckpointRepository.Watermark next = source.watermark();
                checkpoints.advanceFastWatermark(
                        authority,
                        cursor,
                        next,
                        runId
                );
                cursor = next;
                processed++;
            }
            if (batch.size() < queryLimit) {
                return true;
            }
        }
        return false;
    }

    private void processRescanLane(
            AtomicBoolean authorityLost,
            DreamLeaseManager.Authority authority,
            UUID runId,
            DreamPolicyResolver.ResolvedDreamPolicy policy,
            DreamBudget budget,
            String initialEncodedCursor,
            int reservation
    ) {
        if (reservation <= 0) {
            return;
        }
        String encodedCursor = initialEncodedCursor;
        DreamRescanCursor cursor = DreamRescanCursor.decode(encodedCursor);
        int processed = 0;
        while (processed < reservation) {
            requireContinue(authorityLost, budget);
            int queryLimit = Math.min(
                    policy.dream().batchSize(),
                    reservation - processed
            );
            List<DreamSourceRepository.DreamSource> batch =
                    sources.findRescanAfter(cursor, queryLimit);
            if (batch.isEmpty()) {
                if (encodedCursor != null) {
                    rescanCheckpoints.advance(
                            authority,
                            encodedCursor,
                            null,
                            runId
                    );
                }
                return;
            }
            for (DreamSourceRepository.DreamSource source : batch) {
                requireContinue(authorityLost, budget);
                discovery.discover(
                        List.of(source),
                        runId,
                        authority,
                        policy,
                        budget,
                        "rescan"
                );
                DreamRescanCursor next = source.rescanCursor();
                rescanCheckpoints.advance(
                        authority,
                        encodedCursor,
                        next,
                        runId
                );
                encodedCursor = next.encode();
                cursor = next;
                processed++;
            }
            if (batch.size() < queryLimit) {
                rescanCheckpoints.advance(
                        authority,
                        encodedCursor,
                        null,
                        runId
                );
                return;
            }
        }
    }

    private void requireContinue(
            AtomicBoolean authorityLost,
            DreamBudget budget
    ) {
        budget.requireTime();
        if (authorityLost.get()) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream authority was lost during run"
            );
        }
        if (!switches.enabled()) {
            throw new DreamRuntimeDisabledException();
        }
    }

    private void finalizeFailure(
            UUID runId,
            DreamLeaseManager.Authority authority,
            DreamBudget budget,
            RuntimeException failure
    ) {
        try {
            if (failure instanceof DreamRuntimeDisabledException) {
                runs.finishAuthoritative(
                        runId,
                        authority,
                        DreamRunRepository.RunOutcome.CANCELLED,
                        budget.snapshot(),
                        "RUNTIME_DISABLED"
                );
            } else if (leases.isOwned(authority)) {
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
            metrics.run(
                    "partial_budget",
                    Duration.between(started, Instant.now())
            );
            return RunResult.PARTIAL_BUDGET;
        } catch (DreamLeaseManager.LostDreamAuthorityException lost) {
            runs.markLostOwnership(runId, authority, budget.snapshot());
            metrics.run(
                    "lost_ownership",
                    Duration.between(started, Instant.now())
            );
            return RunResult.LOST_OWNERSHIP;
        }
    }

    public enum RunResult {
        DISABLED,
        APPLY_MODE_UNSUPPORTED,
        STANDBY,
        LOCAL_OVERLAP_SKIPPED,
        SUCCEEDED,
        PARTIAL_BUDGET,
        LOST_OWNERSHIP,
        FAILED
    }

    private static final class DreamRuntimeDisabledException
            extends RuntimeException {
    }
}
