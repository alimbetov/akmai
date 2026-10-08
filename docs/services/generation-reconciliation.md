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
- existing PostgreSQL reconciliation tests for published-generation preservation;
- concurrency/publication race coverage remains required before `VERIFIED`.

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

### RECON-BR-07 — RETIRED requires tombstone verification

Rule: a `RETIRED` generation may become `CLEANED` only after the corresponding retired-generation tombstone is `PURGED` or already `VERIFIED`.

Negative:
- missing/incompatible tombstone -> fail rather than silently marking generation cleaned.

### RECON-BR-08 — FAILED generations do not require retired tombstones

Rule: a failed staging/publication generation may be repaired and cleaned after residual storage reaches zero without manufacturing retired-publication evidence.

### RECON-BR-09 — physical repair is bounded

Rule: `GenerationRepairService` deletes at most configured batches and each repair pass uses the bounded `repairTransactionTemplate` with `PROPAGATION_REQUIRES_NEW`.

Invariants:
- reconciliation must not create an unbounded transaction;
- repair can commit bounded progress independently while outer lifecycle authority remains locked;
- retry after partial physical cleanup is safe.

### RECON-BR-10 — tombstone retention cleanup is partitioned

Rule: expired `VERIFIED` tombstones are deleted in bounded batches using `FOR UPDATE SKIP LOCKED`.

This `SKIP LOCKED` use is valid because the delete is completed in the same SQL transaction that owns the selected rows; it is not used as a cross-transaction ownership claim.

## Positive cases

- `RETIRED` generation with residual vector/projection rows is fully repaired and becomes `CLEANED`.
- `FAILED` generation with no residual rows becomes `CLEANED`.
- `RETIRING` generation creates durable `PURGING`, purges physical rows, then becomes `RETIRED`.
- later run verifies a `PURGED` tombstone and advances `RETIRED -> CLEANED`.
- two different candidates may be handled independently by different replicas.
- stale PURGING work is recoverable.

## Negative / failure cases

- current published generation -> never cleaned;
- candidate advisory lock busy -> skip without blocking;
- fresh PURGING tombstone -> do not steal;
- incompatible tombstone state -> fail explicitly;
- missing embedding profile -> fail explicitly;
- residual rows remain after bounded repair -> defer, do not falsely finalize;
- tombstone finalization update count != 1 -> fail;
- generation state changes before finalization -> fail;
- cleanup transaction timeout/deadlock -> transaction rolls back and later scheduler run retries;
- application crash after PURGING commit but before physical cleanup -> stale PURGING recovery path resumes work.

## Transaction and failure boundary

`cleanupTransactionTemplate` owns lifecycle/generation state transitions and is timeout-bounded.

`repairTransactionTemplate` is `REQUIRES_NEW` and timeout-bounded. Physical cleanup therefore commits in bounded passes. The outer cleanup transaction continues to hold lifecycle/generation authority while terminal repair is performed.

`RETIRING` intentionally has two cleanup transactions:

```text
TX-A: lifecycle lock -> generation lock -> durable PURGING claim -> COMMIT
TX-B: advisory lock -> lifecycle lock -> generation lock -> repair/finalize -> COMMIT
```

No external model/network call belongs in either transaction.

## Concurrency semantics

The scheduler fires on every pod. Correctness uses three layers:

1. candidate transaction advisory lock prevents two reconcilers from actively contending on the same candidate transaction;
2. lifecycle + generation row locks remain final mutation authority and preserve publication race safety;
3. the persisted `PURGING` tombstone bridges the transaction gap in the RETIRING two-phase protocol.

The advisory key is derived deterministically from document ID and generation. Collision is fail-safe: unrelated work can be skipped temporarily, but authority is never widened.

## Timeout / cancellation

- scheduler work is bounded by `max-batches-per-run` and `batch-size`;
- cleanup and repair DB transactions have explicit timeouts;
- no asynchronous fire-and-forget repair is permitted;
- transaction rollback releases advisory locks automatically.

## Observability

Existing scheduler metrics cover outcome, cleaned count, batches and duration. Reconciliation additionally logs candidate-busy, fresh-purge-claim and stale-purge-reclaimed events.

Follow-up before `VERIFIED`: decide whether candidate-busy/stale-reclaim counts merit dedicated counters after observing production frequency; do not add unbounded-cardinality document/generation labels.

## Recovery

- transaction failure: rollback; next scheduler tick retries;
- crash after PURGING commit: stale tombstone becomes reclaimable;
- crash during terminal transaction: row/advisory locks are released by PostgreSQL and committed repair passes remain idempotently repairable;
- residual cleanup exhausted: state remains eligible and carries `last_error` plus audit evidence.

## Tests

Primary:

- `GenerationReconciliationMultipodIntegrationTest`
- `PostgresVectorReconciliationIntegrationTest`
- `GenerationRepairServiceIntegrationTest`
- `GenerationReconciliationSchedulerObservabilityTest`

Before promotion to `VERIFIED`, exact PR-head CI must be green and the existing publication-race/published-generation preservation tests must remain green.

## Definition of Done

- [x] Business rule documented
- [x] Happy path tests exist
- [x] Multi-pod busy-candidate case tested
- [x] Fresh/stale PURGING recovery tested
- [x] Failure/rollback semantics documented
- [x] Concurrency semantics documented
- [x] Observability defined
- [x] Operational recovery described
- [ ] Final exact PR-head CI/quality/storage/image checks green
- [ ] Inventory promoted from DRAFT only after final verification
