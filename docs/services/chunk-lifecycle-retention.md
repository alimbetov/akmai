# Chunk lifecycle / retention

Status: **DRAFT**

This contract describes current TTL visibility and physical retention behavior. Code, Liquibase schema and application configuration remain authoritative.

## Purpose

Retention has two distinct responsibilities:

1. **synchronous visibility safety** — expired content must stop participating in retrieval immediately at read time, independent of scheduler lag;
2. **asynchronous physical cleanup** — expired published generations are claimed, fenced, deleted atomically, tombstoned, retired and eventually reconciled/verified.

The scheduler is a cleanup mechanism. It is not the visibility boundary.

## Entry points

- `PostgresPublishedLifecycleEligibility` — synchronous TTL / publication / ACL visibility fence;
- `RetentionScheduler.cleanupExpiredDocuments()` — scheduled run boundary;
- `RetentionWorkerPool.drain(...)` — bounded local dispatch;
- `RetentionClaimRepository.claimExpired(...)` — PostgreSQL multi-pod work claim;
- `ChunkRetentionService.cleanup(...)` — fenced physical deletion transaction;
- `GenerationReconciliationService` — later verification / residual repair of retired generations.

## Process

### A. Read-time TTL fence

Retrieval eligibility requires the lifecycle row to remain published and active and requires:

```sql
expires_at IS NULL OR expires_at > clock_timestamp()
```

Therefore an expired row becomes invisible synchronously even when `RetentionScheduler` has not executed yet.

### B. Scheduler run

`RetentionScheduler`:

1. exits when retention is disabled;
2. recovers abandoned ingestion rows in bounded batches;
3. computes the run claim budget as `batchSize * maxBatchesPerRun`;
4. delegates bounded work to `RetentionWorkerPool`;
5. reports eligible backlog and retry-exhausted rows;
6. preserves a primary run failure if a post-run observation query also fails.

### C. Multi-pod claim

`RetentionClaimRepository.claimExpired(...)` executes in a transaction and:

1. takes `FOR SHARE` on `knowledge_embedding_runtime`;
2. pauses retention claims unless embedding migration status is `IDLE`;
3. selects expired TTL lifecycle rows with `FOR UPDATE SKIP LOCKED`;
4. excludes documents with a `STAGING` generation;
5. excludes rows with `attempt_count >= retryLimit`;
6. permits reclaim of expired `DELETE_PENDING` / `DELETING` leases;
7. writes a new `claim_id`, `claimed_by`, `claim_generation` and DB-time `lease_until`;
8. transitions the row to `DELETE_PENDING` in the same transaction.

Claim selection and claim mutation are in the same transaction, so `SKIP LOCKED` is a valid work-partitioning primitive here.

### D. Worker dispatch

`RetentionWorkerPool` enforces local bounded parallelism with a semaphore and bounded executor. A claim that cannot be submitted is released back to `ACTIVE` only if the current claim identity still matches.

A drain run refills capacity after tasks complete until the run budget is exhausted or no eligible work remains.

### E. Physical cleanup transaction

`ChunkRetentionService.cleanup(...)` runs under `cleanupTransactionTemplate`.

The cleanup transaction timeout is configured to be less than half of `leaseDuration`; this is required to prevent normal cleanup work from outliving its ownership lease.

Within the transaction:

1. lock lifecycle row and validate claim identity, published generation, status and live lease;
2. transition `DELETE_PENDING -> DELETING`;
3. lock target generation;
4. validate embedding profile, vector manifest and projection counts;
5. create `knowledge_retired_generation` tombstone in `PURGING`;
6. delete vectors;
7. delete graph associations;
8. delete projections, references, identifiers and vector manifest;
9. verify residual payload count is zero;
10. revalidate claim fence;
11. finalize tombstone `PURGING -> PURGED`;
12. transition generation `PUBLISHED -> RETIRED`, `cleanup_required=true`;
13. transition lifecycle to `DELETED` and clear `published_generation` and claim fields;
14. commit atomically.

Any exception before commit rolls back payload deletion, tombstone mutation, generation retirement and lifecycle deletion together.

## Business rules

### RET-BR-01 — TTL visibility is synchronous

Scheduler delay must never make expired content retrievable.

### RET-BR-02 — scheduler is not singleton authority

Multiple pods may run the scheduler. Work ownership is partitioned by transactional `FOR UPDATE SKIP LOCKED` claims plus claim identity / lease fencing.

### RET-BR-03 — database time is lease authority

Claim expiry and TTL comparisons use PostgreSQL `clock_timestamp()`.

### RET-BR-04 — embedding migration pauses new claims

Retention does not claim physical deletion work while embedding migration status is non-`IDLE`.

### RET-BR-05 — staging generation blocks physical deletion

A document with any `STAGING` generation is not claimable for TTL cleanup.

### RET-BR-06 — worker ownership is explicit

Mutation requires exact `documentId + generation + claimId + workerId` and a live lease.

### RET-BR-07 — expired ownership is reclaimable

Expired `DELETE_PENDING` or `DELETING` rows may be claimed by another worker.

### RET-BR-08 — stale owners fail closed

An owner that loses its lease or claim identity must not finalize deletion or increment retry state.

### RET-BR-09 — physical deletion is atomic

Partial payload deletion must not survive rollback.

### RET-BR-10 — tombstone precedes committed retirement

A successful hot purge must leave a durable retired-generation tombstone describing the deleted payload.

### RET-BR-11 — retry failure is fenced

`markFailed` increments `attempt_count` only while the same claim still owns a live lease.

### RET-BR-12 — retry exhaustion is logical dead-letter state

No new schema state is introduced. A lifecycle row is retry-exhausted when:

```text
retention_status = DELETE_FAILED
AND attempt_count >= retryLimit
```

Such rows are excluded from claimable backlog and are reported separately by `countRetryExhausted(...)`. They require operator recovery or a later explicit remediation mechanism; they must not silently appear as zero backlog.

### RET-BR-13 — cleanup timeout must remain below half the lease

Startup configuration must reject `cleanupTransactionTimeout >= leaseDuration / 2`.

### RET-BR-14 — primary scheduler failure is preserved

If the main scheduler run fails and backlog/dead-letter observation also fails, the main failure is rethrown and the observation failure is attached as suppressed.

### RET-BR-15 — scheduler work is bounded

Each scheduled run has a deterministic maximum claim budget and local worker parallelism.

## Positive cases

- TTL row expires and is immediately filtered from retrieval before cleanup executes;
- two pods claim different expired rows rather than blocking each other;
- expired lease is reclaimed by another pod;
- successful cleanup removes all hot payload and commits tombstone + retirement + lifecycle deletion atomically;
- retryable `DELETE_FAILED` remains eligible while `attempt_count < retryLimit`;
- retry-exhausted failure is excluded from work backlog and reported separately.

## Negative / degraded cases

- embedding migration active -> no new retention claims;
- document has STAGING generation -> no claim;
- live claim owned by another worker -> no claim;
- stale claim enters cleanup -> `STALE_CLAIM`, no destructive finalize;
- manifest/projection/vector consistency mismatch -> transaction rollback and fenced `DELETE_FAILED` when ownership remains live;
- audit/persistence failure after payload deletion -> full transaction rollback;
- worker submission rejected -> claim released if still owned;
- cleanup transaction timeout -> rollback; failure bookkeeping only while lease remains authoritative;
- post-run backlog observation fails -> scheduler reports failure without masking an earlier run failure.

## Retry / dead-letter semantics

`attempt_count` is incremented by fenced `markFailed` only for an owner whose lease remains live. A stale owner cannot consume retry budget.

Rows with attempts below `retryLimit` are retryable `DELETE_FAILED`. Rows at or above the limit are logical dead letters and are not automatically reclaimed.

Operational recovery must preserve the invariant that retry budget is reset only by an explicit operator/remediation action, not by another stale worker.

## Concurrency / locking

Locking authority is:

```text
embedding runtime FOR SHARE
    -> candidate lifecycle FOR UPDATE SKIP LOCKED during claim
    -> committed claim identity / lease
    -> lifecycle FOR UPDATE during cleanup
    -> generation FOR UPDATE
```

The claim transaction is short. Expensive physical deletion is performed later under claim fencing rather than while holding the original claim-selection lock.

## Observability

Current signals include:

- `akmai.retention.backlog` — eligible cleanup rows;
- `akmai.retention.run{outcome}` — scheduler run duration/outcome;
- retention claim/result/stale-claim/lease-lost counters;
- scheduler completion log fields `backlog` and `retryExhausted`;
- audit event `HOT_PAYLOAD_PURGED` for successful physical purge.

`retryExhausted` is intentionally distinct from eligible backlog.

## Tests

Primary evidence:

- `PostgresPublishedLifecycleEligibilityIntegrationTest` — synchronous TTL visibility fence;
- `RetentionClaimRepositoryMultipodIntegrationTest` — `SKIP LOCKED`, lease takeover and migration pause;
- `RetentionBacklogIntegrationTest` — eligible backlog and retry-exhausted separation;
- `ChunkRetentionServiceIntegrationTest` — atomic successful purge and rollback after late failures;
- `ChunkRetentionServiceTest` — stale/failure behavior;
- `RetentionWorkerPoolTest` — bounded worker / claim submission behavior;
- `RetentionSchedulerObservabilityTest` — backlog reporting and primary-failure preservation;
- `TransactionTemplatesConfigurationTest` — cleanup timeout vs lease invariant.

## Recovery

- expired worker lease: automatically reclaimable;
- retryable `DELETE_FAILED`: automatically claimable until retry limit;
- retry-exhausted `DELETE_FAILED`: operator/remediation action required;
- residual retired-generation inconsistencies: handled by generation reconciliation, not by weakening the retention claim fence.

## Definition of Done

- [x] synchronous TTL boundary documented
- [x] claim and lease authority documented
- [x] multi-pod `SKIP LOCKED` behavior tested
- [x] expired lease takeover tested
- [x] migration pause tested
- [x] atomic purge / rollback tested
- [x] retry-exhausted rows separately observable
- [x] primary scheduler failure preservation tested
- [x] timeout/lease invariant documented and tested
- [ ] final exact PR head passes required CI/quality/storage/image gates

Promote this contract from `DRAFT` to `VERIFIED` only after the final exact head SHA passes the required verification gates.
