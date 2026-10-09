# Generation reconciliation

Status: **DRAFT**

## Purpose

Generation reconciliation removes residual retrieval/storage rows from generations that are no longer authoritative, preserves durable purge evidence, and advances terminal generation lifecycle to `CLEANED` without touching the currently published generation.

This process is a multi-replica background worker. Correctness authority remains PostgreSQL lifecycle/generation state; scheduler cadence is not an authority boundary.

## Entry points

- `GenerationReconciliationScheduler.reconcile()` — periodic trigger on every application replica.
- `GenerationReconciliationService.reconcileBatch()` — bounded reconciliation batch.
- `GenerationRepairService.repair(...)` — bounded physical cleanup passes in `REQUIRES_NEW` repair transactions.

## Process

```text
scheduler tick on every pod
  -> reconciliation enabled?
  -> discover bounded RETIRING / RETIRED / FAILED candidates
  -> for each candidate
       -> begin bounded cleanup transaction
       -> try candidate-scoped PostgreSQL transaction advisory lock
          -> busy: skip immediately; do not wait on lifecycle/generation row locks
          -> acquired: continue
       -> lock document lifecycle
       -> fail closed if candidate is now published
       -> lock generation
       -> branch by generation_status
       -> candidate-local durable-state inconsistency?
          -> rollback candidate transaction
          -> persist last_error + GENERATION_RECONCILIATION_FAILED best-effort
          -> continue with the next candidate
       -> infrastructure / transaction / JDBC failure?
          -> fail the batch immediately
          -> rollback active transaction
          -> let scheduler-level failure handling surface outage/timeout

RETIRING
  phase A:
    -> insert PURGING tombstone if absent
       OR reclaim only a stale PURGING tombstone
    -> COMMIT durable purge claim
  phase B:
    -> begin new bounded cleanup transaction
    -> reacquire candidate advisory lock
    -> re-lock lifecycle + generation
    -> recheck not published
    -> bounded physical repair
    -> residual rows == 0 ?
         yes -> tombstone PURGED + generation RETIRED
         no  -> keep RETIRING/PURGING and record deferred error

RETIRED / FAILED
  -> bounded physical repair while lifecycle/generation locks are held
  -> residual rows == 0 ?
       no  -> retain terminal status + cleanup_required and record deferred error
       yes -> verify RETIRED tombstone when applicable
           -> generation CLEANED
           -> require updated row count == 1
           -> append success audit after the state transition
           -> cleanup_required=false

end of batch
  -> bounded deletion of expired VERIFIED tombstones using SKIP LOCKED
```

## Business rules

### RECON-BR-01 — published generation is never reconciled destructively

Rule: destructive cleanup requires a lifecycle lock and a second check that `published_generation != candidate.generation`.

Positive:
- retired generation `N` while generation `N+1` is published may be cleaned.

Negative:
- candidate becomes the published generation after discovery -> cleanup returns without deleting its retrieval state.

Invariants:
- candidate discovery is never sufficient authority for deletion;
- publication/lifecycle state is re-read under lock.

Tests:
- `GenerationReconciliationFailureMatrixIntegrationTest.currentlyPublishedGenerationIsNeverSelectedForRepair`;
- existing PostgreSQL reconciliation tests for published-generation preservation.

### RECON-BR-02 — candidate ownership is non-blocking across pods

Rule: before taking lifecycle/generation row locks, a worker must acquire `pg_try_advisory_xact_lock(candidateKey)` inside the active cleanup transaction.

Positive:
- one worker acquires the candidate key and processes normally.

Negative:
- another worker already owns the same candidate key -> the current worker skips that candidate instead of waiting on the same lifecycle/generation locks.

Invariants:
- advisory lock is transaction-scoped and automatically released on commit/rollback/connection failure;
- a hash collision may reduce concurrency but must never weaken correctness;
- advisory lock is only an optimization/ownership primitive between reconcilers; lifecycle/generation rows remain authoritative.

Tests:
- `GenerationReconciliationMultipodIntegrationTest.busyCandidateIsSkippedWithoutBlockingAndRetriesAfterLockRelease`.

### RECON-BR-03 — lock order remains lifecycle then generation

Rule: candidate ownership must not invert the existing publication/reconciliation row-lock order.

Invariants:
- publication and reconciliation both acquire lifecycle authority before generation row authority;
- the advisory lock does not replace either row lock.

Negative:
- do not implement candidate partitioning by taking a long generation `FOR UPDATE` lock before the lifecycle row; this creates a publication/reconciliation deadlock path.

### RECON-BR-04 — RETIRING cleanup remains two-phase

Rule: a `RETIRING` generation must have a committed `PURGING` tombstone before physical cleanup begins.

Invariants:
- phase A and phase B are separate cleanup transactions;
- a crash after phase A leaves durable evidence from which a later run can recover;
- physical deletion must not happen before the tombstone claim is durable.

### RECON-BR-05 — fresh PURGING work is not stolen

Rule: an existing `PURGING` tombstone is treated as an active claim until it is older than the reconciliation stale threshold.

Current stale threshold:

```text
max(reconciliation.grace-period, reconciliation.fixed-delay)
```

Positive:
- stale `PURGING` claim is reclaimed by incrementing `cleanup_attempts`, refreshing `purge_started_at`, and retrying.

Negative:
- fresh `PURGING` claim -> current worker skips it.

Tests:
- `GenerationReconciliationMultipodIntegrationTest.freshPurgingTombstoneIsNotStolenButStaleClaimIsRecovered`.

### RECON-BR-06 — terminal cleanup is retry-safe

Rule: `RETIRED` and `FAILED` generations remain `cleanup_required=true` until residual retrieval rows are zero and finalization succeeds.

Negative:
- bounded repair limit leaves residual rows -> record `last_error`, emit deferred audit event, keep the generation eligible for a later run.

Tests:
- `GenerationReconciliationFailureMatrixIntegrationTest.boundedResidualIsDeferredAndNextRunCanFinishCleanup`.

### RECON-BR-07 — RETIRED requires tombstone verification

Rule: a `RETIRED` generation may become `CLEANED` only after the corresponding retired-generation tombstone is `PURGED` or already `VERIFIED`.

Negative:
- missing/incompatible tombstone -> candidate-local state failure; do not mark generation cleaned and do not terminate processing of unrelated candidates.

Tests:
- `GenerationReconciliationFailureMatrixIntegrationTest.corruptRetiredTombstoneDoesNotStarveHealthyCandidate`.

### RECON-BR-08 — FAILED generations do not require retired tombstones

Rule: a failed staging/publication generation may be repaired and cleaned after residual storage reaches zero without manufacturing retired-publication evidence.

### RECON-BR-09 — physical repair is bounded

Rule: `GenerationRepairService` deletes at most configured batches and each repair pass uses the bounded `repairTransactionTemplate` with `PROPAGATION_REQUIRES_NEW`.

Invariants:
- reconciliation must not create an unbounded transaction;
- repair can commit bounded progress independently while outer lifecycle authority remains locked;
- retry after partial physical cleanup is safe.

Tests:
- `GenerationRepairServiceIntegrationTest.repairCommitsBoundedBatchesAndNeverMutatesTombstone`;
- `GenerationReconciliationFailureMatrixIntegrationTest.boundedResidualIsDeferredAndNextRunCanFinishCleanup`.

### RECON-BR-10 — tombstone retention cleanup is partitioned

Rule: expired `VERIFIED` tombstones are deleted in bounded batches using `FOR UPDATE SKIP LOCKED`.

This `SKIP LOCKED` use is valid because the delete is completed in the same SQL transaction that owns the selected rows; it is not used as a cross-transaction ownership claim.

### RECON-BR-11 — candidate state corruption is isolated, infrastructure failure is not

Candidate-local durable-state inconsistencies use `CandidateStateException` and are isolated to the affected generation. Examples:

- missing embedding profile metadata;
- incompatible/missing retired-generation tombstone;
- unexpected terminal state transition row count;
- invalid RETIRING cleanup state.

The failing candidate remains retryable and receives bounded `last_error` diagnostic state plus a best-effort `GENERATION_RECONCILIATION_FAILED` audit event. The batch continues with later candidates.

By contrast, JDBC, transaction, query-timeout, deadlock and other infrastructure failures are not converted into candidate-local failures. They propagate to the scheduler and fail the run.

This distinction prevents one corrupt generation from starving the queue while still treating database availability/timeout failures as system-level incidents.

Tests:
- `GenerationReconciliationFailureMatrixIntegrationTest.corruptRetiredTombstoneDoesNotStarveHealthyCandidate`;
- `GenerationReconciliationFailureMatrixIntegrationTest.databaseFailureIsFailFastAndLeavesGenerationRetryable`;
- `GenerationReconciliationFailureMatrixIntegrationTest.repairTimeoutRollsBackPassAndLeavesPayloadRetryable`.

### RECON-BR-12 — success audit reflects transitioned state

A terminal success event (`GENERATION_VERIFIED` or `GENERATION_REPAIRED`) is appended only after the generation update to `CLEANED` returns exactly one affected row.

If the final transition affects zero rows, reconciliation throws a candidate-state failure and no success event is committed for that attempt.

This ordering prevents optimistic audit records from claiming successful cleanup before durable lifecycle state has transitioned.

## Orphan / residual cleanup scope

In this service contract, an orphan retrieval payload means retrieval/storage rows belonging to a generation that is no longer publication-authoritative but whose `knowledge_document_generation` metadata still exists in `RETIRING`, `RETIRED`, or `FAILED` state. Those rows are discoverable and repairable because generation identity, access level and embedding profile remain available.

Rows whose owning `knowledge_document_generation` row is physically absent are not inferred or deleted by this worker. Deleting such rows safely would require a separate integrity sweep that can establish profile/table ownership and publication safety without generation metadata. This worker must not guess that authority.

## Positive cases

- `RETIRED` generation with residual vector/projection rows is fully repaired and becomes `CLEANED`.
- `FAILED` generation with no residual rows becomes `CLEANED`.
- `RETIRING` generation creates durable `PURGING`, purges physical rows, then becomes `RETIRED`.
- later run verifies a `PURGED` tombstone and advances `RETIRED -> CLEANED`.
- two different candidates may be handled independently by different replicas.
- stale PURGING work is recoverable.
- corrupt tombstone/profile state on one candidate does not prevent a healthy candidate in the same batch from progressing.

## Negative / failure cases

- current published generation -> never cleaned;
- candidate advisory lock busy -> skip without blocking;
- fresh PURGING tombstone -> do not steal;
- incompatible/missing tombstone state -> candidate-local failure, remain retryable;
- missing embedding profile -> candidate-local failure, remain retryable;
- residual rows remain after bounded repair -> defer, do not falsely finalize;
- tombstone finalization update count != 1 -> candidate-local failure;
- generation state changes before finalization -> candidate-local failure;
- JDBC/transaction/query-timeout/deadlock -> fail the run, rollback the active transaction, later scheduler run retries;
- application crash after PURGING commit but before physical cleanup -> stale PURGING recovery path resumes work.

## Transaction and failure boundary

`cleanupTransactionTemplate` owns lifecycle/generation state transitions and is timeout-bounded.

`repairTransactionTemplate` is `REQUIRES_NEW` and timeout-bounded. Physical cleanup therefore commits in bounded passes. The outer cleanup transaction continues to hold lifecycle/generation authority while terminal repair is performed.

`RETIRING` intentionally has two cleanup transactions:

```text
TX-A: lifecycle lock -> generation lock -> durable PURGING claim -> COMMIT
TX-B: advisory lock -> lifecycle lock -> generation lock -> repair/finalize -> COMMIT
```

Candidate-state failure bookkeeping runs in a new cleanup transaction after the failed candidate transaction has rolled back. Failure-bookkeeping itself is best-effort and must not turn one candidate corruption into a system-wide batch outage.

No external model/network call belongs in these transactions.

## Concurrency semantics

The scheduler fires on every pod. Correctness uses three layers:

1. candidate transaction advisory lock prevents two reconcilers from actively contending on the same candidate transaction;
2. lifecycle + generation row locks remain final mutation authority and preserve publication race safety;
3. the persisted `PURGING` tombstone bridges the transaction gap in the RETIRING two-phase protocol.

The advisory key is derived deterministically from document ID and generation. Collision is fail-safe: unrelated work can be skipped temporarily, but authority is never widened.

## Timeout / cancellation

- scheduler work is bounded by `max-batches-per-run` and `batch-size`;
- cleanup and repair DB transactions have explicit timeouts;
- `repairTransactionTemplate` timeout must reach the blocking JDBC delete, not merely a between-step timer;
- timeout rolls back the current repair pass and leaves the generation `cleanup_required=true`;
- no asynchronous fire-and-forget repair is permitted;
- transaction rollback releases advisory locks automatically.

`GenerationReconciliationFailureMatrixIntegrationTest.repairTimeoutRollsBackPassAndLeavesPayloadRetryable` exercises a real PostgreSQL row lock so the configured repair transaction timeout interrupts a blocking delete and leaves the payload/generation retryable.

## Observability

Existing scheduler metrics cover outcome, cleaned count, batches and duration. Reconciliation additionally logs:

- `candidate_busy`;
- `fresh_purge_claim`;
- `stale_purge_reclaimed`;
- `candidate_state_failed`;
- `candidate_failure_bookkeeping_failed`.

Durable audit events distinguish:

- purge started/deferred;
- repair deferred;
- repair/verification success;
- candidate-local reconciliation failure.

Do not add document/generation identifiers as metric labels; they remain log/audit dimensions only.

## Recovery

- candidate-state inconsistency: generation remains retryable, diagnostic state is persisted best-effort, unrelated candidates continue;
- infrastructure/transaction failure: rollback/fail run; next scheduler tick retries;
- crash after PURGING commit: stale tombstone becomes reclaimable;
- crash during terminal transaction: row/advisory locks are released by PostgreSQL and committed repair passes remain idempotently repairable;
- residual cleanup exhausted: state remains eligible and carries `last_error` plus audit evidence;
- timeout during a repair pass: the current pass rolls back; previously committed bounded passes remain safe to retry.

## Tests

Primary:

- `GenerationReconciliationFailureMatrixIntegrationTest`
- `GenerationReconciliationMultipodIntegrationTest`
- `PostgresVectorReconciliationIntegrationTest`
- `GenerationRepairServiceIntegrationTest`
- `GenerationReconciliationSchedulerObservabilityTest`

The failure matrix covers:

1. missing RETIRED tombstone isolation;
2. healthy candidate progress after a corrupt candidate;
3. bounded residual deferral and retry completion;
4. current-publication protection;
5. fail-fast database errors;
6. real PostgreSQL repair timeout rollback.

Before promotion to `VERIFIED`, exact PR-head CI must be green and the existing publication/reconciliation tests must remain green.

## Definition of Done

- [x] Business rule documented
- [x] Happy path tests exist
- [x] Multi-pod busy-candidate case tested
- [x] Fresh/stale PURGING recovery tested
- [x] Bounded residual + retry completion tested
- [x] Tombstone inconsistency isolation tested
- [x] Infrastructure DB failure remains fail-fast
- [x] PostgreSQL lock timeout rollback tested
- [x] Success audit ordered after CLEANED transition
- [x] Failure/rollback semantics documented
- [x] Concurrency semantics documented
- [x] Observability defined
- [x] Operational recovery described
- [ ] Final exact PR-head CI/quality/storage/image checks green
- [ ] Inventory promoted from DRAFT only after final verification
