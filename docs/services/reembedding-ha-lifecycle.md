# Re-embedding HA / lifecycle contract

Status: **DRAFT** until one exact PR-head SHA passes CI, Retrieval Quality Gate, Retrieval Storage Final Benchmark, and Production Image Build.

## Purpose

`ReembeddingService` migrates the published corpus from the active embedding profile to the configured profile without exposing a mixed-profile publication state. Multi-replica authority is provided by `ReembeddingLeaseManager` using a persisted owner id, PostgreSQL-time lease, and monotonically increasing fencing token.

## Entry points

- `ReembeddingStartupRunner.run()` performs startup recovery and optional auto-migration.
- `ReembeddingService.migrateToConfiguredProfile()` performs the migration lifecycle.
- `ReembeddingService.recoverExpiredMigration()` claims and aborts one expired active migration.
- `ReembeddingLeaseHeartbeatScheduler.heartbeat()` renews live migrations owned by the local replica independently of blocking model calls.

## Lifecycle

1. Resolve active and configured embedding profiles.
2. If they are identical, return without migration.
3. In one transaction, transition runtime `IDLE -> PREPARING`, create the migration row, and claim owner/lease/fencing authority.
4. Drain active ingestion staging work and live retention leases while renewing authority.
5. Snapshot the published corpus and transition migration/runtime to `STAGING`.
6. For each snapshot document, allocate a REEMBEDDING generation, compute target embeddings outside the staging write transaction, then persist projections/identifiers/references/vector manifest/vectors and mark the migration document `VERIFIED`.
7. Require every migration document to be `VERIFIED`, then transition to `READY_TO_CUTOVER`.
8. In one cutover transaction, revalidate the snapshot, lock lifecycle rows, retire source generations, publish candidate generations, switch lifecycle pointers, set the target profile active, return runtime to `IDLE`, and mark the migration `COMPLETED`.
9. On failure, abort only while current owner/fencing authority is still valid. Stale owners are fenced from cleanup.

## Authority and fencing rules

- PostgreSQL `clock_timestamp()` is authoritative for lease expiry.
- `claimNew()` succeeds only for an unowned migration with fencing token `0`.
- `claimExpiredActive()` uses `FOR UPDATE SKIP LOCKED`, replaces the owner, and increments the fencing token.
- `renew()` requires the same migration id, owner id, fencing token, active status, and a still-live lease.
- Once a lease has expired, the old owner cannot revive it with `renew()`.
- Once another replica has taken over, stale owner writes guarded by owner/fencing predicates must affect zero rows and fail.
- Heartbeat renewal is independent of the synchronous migration call path so a long embedding/model call does not automatically expire an otherwise healthy owner.

## Configuration invariant

`akmai.reembedding.heartbeat-interval` must be positive and strictly below half of `akmai.reembedding.lease-duration`. The compatibility constructor used by tests and non-Binder callers derives its default heartbeat from one quarter of the configured lease, capped at 15 seconds, so short test leases remain valid without weakening the production invariant.

## Startup recovery

Startup recovery does not abort a migration merely because it is active. It first calls `claimExpiredActive()`; therefore:

- a migration with a live remote lease is left untouched;
- an expired migration is taken over with a higher fencing token;
- only the new owner may abort it and reset embedding runtime state.

After recovery, optional auto-migration runs only when the configured profile differs from the active profile.

## Positive cases

- Active profile already equals configured profile: no migration row or cutover is required.
- Whole-corpus migration succeeds: all candidate generations are verified, one atomic cutover publishes them, and the target embedding profile becomes active.
- Healthy long-running migration: scheduler heartbeat keeps ownership live independently of model-call latency.
- Expired owner: another replica claims the migration, receives a higher fence, and stale renewals are rejected.
- Startup with live remote owner: recovery is a no-op.
- Startup with expired owner: recovery claims first and then aborts under the new fence.

## Negative / failure cases

- Active runtime profile changes before ownership is established: migration fails before staging.
- Drain deadline expires: migration abort path runs while authority is valid.
- Published corpus contains a mixed source embedding profile: snapshot fails closed.
- Source publication changes during candidate allocation: candidate staging fails.
- Target embedding/model call fails: source publication remains authoritative and candidate generation is failed/cleaned through the abort path.
- Any migration document is not verified: transition to `READY_TO_CUTOVER` is rejected.
- Snapshot/lifecycle/source/candidate/profile validation changes before cutover: cutover fails before publication switch.
- Unexpected row counts during source-retire/candidate-publish/lifecycle-switch: the cutover transaction rolls back.
- Lease expires or another replica takes over: stale owner loses authority and must not continue authoritative cleanup or cutover.

## Transaction boundaries

- Begin, snapshot transition, candidate allocation, candidate persistence, ready-to-cutover, cutover, and abort are explicit database transactions through the dedicated re-embedding transaction template.
- Embedding/model computation is performed outside the candidate persistence transaction.
- Cutover is atomic across source retirement, candidate publication, lifecycle pointer switch, active-profile change, and migration completion.

## Tests

Current executable coverage includes `ReembeddingIntegrationTest` for:

- successful whole-document profile migration;
- target embedding failure preserving source publication and active profile;
- live lease not being stealable by another replica;
- expired lease takeover incrementing the fencing token;
- stale-owner renew rejection;
- startup recovery ignoring a live remote lease;
- startup recovery claiming an expired migration before abort.

`ReembeddingPropertiesTest` additionally covers the heartbeat/lease startup invariant and the short-lease compatibility constructor.

## Observability

Operational signals should distinguish at minimum:

- current migration id/status;
- owner id/fencing token/lease expiry;
- heartbeat/renew failure;
- startup recovery takeover;
- per-document staging failure;
- cutover failure;
- migration completion/abort.

The persisted migration and migration-document rows are the durable forensic source for owner, fence, lifecycle state and last error.

## Recovery

- Do not manually reset runtime state while a live owner exists.
- For a crashed owner, allow the lease to expire; startup recovery or another replica may then claim the migration with a higher fence and abort it safely.
- Failed REEMBEDDING generations remain cleanup-required and are handled by normal generation reconciliation/retention cleanup paths.
- A stale replica must be treated as non-authoritative even if it still has in-memory migration state.

## Known hardening item

Failure cleanup must preserve the original migration/staging exception if secondary abort or candidate-failure bookkeeping itself throws. Secondary cleanup failure should be attached as suppressed diagnostic context rather than replace the primary business failure. This item remains part of the current hardening PR until executable coverage is added.
