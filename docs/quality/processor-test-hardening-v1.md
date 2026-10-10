# Processor and background-service test hardening v1

Status: **ACTIVE QUALITY ROADMAP**

Branch: `quality/processor-test-hardening-v1`

## Goal

Increase confidence in AkmAI background processors and orchestration services without duplicating repository-level integration tests. The hardening target is functional correctness under failure and bounded behavior under load: gates, loop bounds, backpressure, lease/fencing handoff, retry/recovery, error isolation, and saturation behavior.

This roadmap intentionally prefers deterministic invariants over fragile wall-clock benchmarks.

## Coverage interpretation

The repository does not currently publish a class-by-class JaCoCo coverage baseline. Therefore this pass classifies coverage by **behavioral evidence**:

- **STRONG** — direct positive/negative tests plus concurrency/failure integration coverage;
- **PARTIAL** — lower layers are tested but the orchestration boundary has uncovered branches;
- **WEAK** — no direct executable test for the production component or only happy-path evidence.

Raw line coverage is not accepted as a substitute for concurrency and failure-model coverage.

## Execution status

Implemented on this branch, but not considered quality-stable until exact-head CI is green:

- `AsyncIngestionScheduler` — direct polling, capacity, claim-failure, rejection/retry and backlog-observation contracts;
- `AsyncIngestionHeartbeat` — renewal success, lost ownership, renewal failure isolation, duplicate registration and close semantics;
- `GenerationReconciliationScheduler` — disabled gate, early-stop, run bound and propagated failure;
- `RetentionScheduler` — disabled gate, stale-recovery bound, drain budget and failure preservation;
- `RetentionWorkerPool` — saturation/backpressure plus recovery/refill after cleanup failure without permit leakage;
- `ReembeddingLeaseHeartbeatScheduler` and `ReembeddingStartupRunner` — delegation, ownership and startup orchestration boundaries;
- `EmbeddingProfileService` — startup ordering, bootstrap when runtime has no active profile, missing-active fail-closed behavior and configured/active mismatch rejection;
- `AdaptiveGraphDreamScheduler` — explicit trigger delegation/failure contract;
- `AdaptiveGraphDreamCoordinator` — deterministic budget exhaustion -> `PARTIAL_BUDGET`, authoritative finalization and no execution of later lanes;
- `RetentionEconomicsSampler` — disabled and failure paths in addition to success metrics;
- `ParallelIngestionExecutor` — large-input bounded execution, maximum in-flight concurrency and cancellation of sibling work after exceptional completion;
- `AdaptiveGraphMaintenanceScheduler` — static/runtime safety gate, runtime disable between batches, early-stop, `maxBatchesPerRun` bound and failure metrics;
- `AuditPartitionMaintenanceScheduler` — startup/cron delegation and visible DB failure contract;
- audit partition PostgreSQL authority — repeated contenders cannot bypass the transaction advisory lock while an owner is active.

Publication replay is not being duplicated blindly: the repository already has publication contract, concurrent publication, persistence-coordinator and async replay-recovery evidence. Additional tests should only be added when they prove a missing side-effect or round-trip invariant.

## Inventory and priority

| Area | Production component | Evidence after this branch | Remaining gap | Priority |
|---|---|---|---|---|
| Async ingestion dispatch | `AsyncIngestionScheduler` | direct scheduler + worker/repository/replay tests | release load qualification | P0 implemented |
| Reconciliation orchestration | `GenerationReconciliationScheduler` | service multipod + observability + direct control-flow tests | exact-head CI | P0 implemented |
| Retention orchestration | `RetentionScheduler`, `RetentionWorkerPool` | worker-pool saturation/failure recovery + observability + PostgreSQL recovery + direct control-flow tests | release load qualification | P0 implemented |
| Embedding profile startup | `EmbeddingProfileService` | direct startup/bootstrap/fail-closed tests + readiness integration | exact-head CI | P0 implemented |
| Re-embedding heartbeat | `ReembeddingLeaseHeartbeatScheduler` | direct scheduler + HA/lease integration | exact-head CI | P0 implemented |
| Dream trigger | `AdaptiveGraphDreamScheduler` | direct scheduler + coordinator/lease/fencing tests | exact-head CI | P0 implemented |
| Retention economics | `RetentionEconomicsSampler` | positive/disabled/failure tests | no material orchestration gap | P1 implemented |
| Ingestion chunk executor | `ParallelIngestionExecutor` | large-input, bounded-concurrency and exceptional-cancellation tests | release load matrix | P1 implemented |
| Generation publication | `GenerationPublicationService` / `PersistenceCoordinator` | publication/replay/concurrency/failure-model evidence | only add call-count tests for a demonstrated missing invariant | P1 reassessed |
| Dream coordinator | `AdaptiveGraphDreamCoordinator` | lease/lost-authority failure model + deterministic budget-stop finalization | wall-clock timeout remains a runtime/load concern, not a fragile CI microbenchmark | P1 implemented |
| Graph maintenance | `AdaptiveGraphMaintenanceScheduler` + maintenance transaction | multipod/integration + direct scheduler bound/failure tests | deeper rollback stress only if a missing invariant is demonstrated | P1 substantially covered |
| Audit partition maintenance | `AuditPartitionMaintenanceScheduler` + PostgreSQL function | direct entry-point/failure test + advisory-lock integration + repeated contender stress | exact-head CI | P2 implemented |

## P0 acceptance contracts

### Async ingestion scheduler

1. A poll never claims more than `min(claimBatchSize, availableDocumentSlots)`.
2. Occupied document slots prevent additional DB claims.
3. Slot reservations are released after a worker finishes.
4. Claim failure releases every reservation, so the next poll can progress.
5. Executor rejection does not lose a durable job: it performs fenced `markRetry` and releases capacity.
6. Backlog metric failure cannot stop durable processing.

### Reconciliation scheduler

1. `enabled=false` performs no reconciliation work.
2. A short batch terminates the run immediately.
3. Full batches cannot exceed `maxBatchesPerRun`.
4. Service failure remains visible to the scheduler/CI and is not converted into success.

### Retention scheduler and worker pool

1. `enabled=false` performs no recovery, claim, or observation work.
2. Stale-ingestion recovery stops on the first partial batch.
3. Recovery cannot exceed `maxBatchesPerRun`.
4. The worker drain budget is exactly `batchSize * maxBatchesPerRun` and is independent of recovered-row count.
5. Existing primary-failure preservation semantics remain intact.
6. Saturated worker permits prevent over-claiming.
7. A cleanup exception releases its permit and the active bounded drain can refill with the next durable claim.

### Embedding profile startup

1. Storage exists before profile persistence/activation is attempted.
2. Empty runtime state bootstraps the configured profile through the same durable path.
3. A runtime reference to a missing profile fails closed.
4. Configured/active profile mismatch fails closed before ingestion/query code can silently mix embedding spaces.

### Heartbeat and Dream trigger

Thin schedulers are tested as explicit contracts: exactly one delegation per trigger and no exception swallowing. Correct distributed authority remains in their already-tested lease/fencing layers.

### Dream coordinator

1. Budget exhaustion is a bounded partial outcome, not a generic failure.
2. Partial-budget finalization records the exact stop reason.
3. Later lanes do not execute after budget exhaustion.
4. Per-run discovery state, heartbeat session and lease are released on the partial path.

## Performance/reliability contracts

Performance tests in this pass MUST be deterministic and CI-safe:

- assert bounded submissions and bounded claims rather than elapsed milliseconds;
- prove backpressure when all permits are occupied;
- prove capacity recovery after completion/failure/rejection;
- prove sibling work is cancelled after an exceptional completion when the processor contract requires fail-fast behavior;
- prove a failed worker task cannot permanently consume an execution permit;
- avoid sleeps where latches/captured tasks can prove state;
- avoid creating one executor per document/chunk;
- preserve existing shared bounded executors;
- large-input smoke tests may use generous timeouts only as hang/deadlock guards, never as microbenchmark gates.

Release/load qualification remains separate and should exercise document concurrency `1 / 3 / 5 / 8`, JDBC-pool saturation, embedding throttling, queue wait p95/p99 and multi-pod lease recovery.

## Remaining work

After exact-head CI is green:

1. run async-ingestion load qualification at document concurrency `1 / 3 / 5 / 8` and record throughput, queue wait p95/p99, DB-pool saturation and retry behavior;
2. run multi-pod failure qualification with worker kill/reclaim, DB timeout, embedding `429/5xx` and publication replay;
3. only add additional rollback/concurrency tests where a concrete missing invariant is found; do not duplicate already-proven repository/transaction contracts;
4. promote this roadmap from `ACTIVE QUALITY ROADMAP` to a completed/verified baseline only after the exact-head CI and release/load evidence are both recorded.

## Exit criteria

This hardening pass is complete only when:

- every P0 component has direct executable positive and negative/control-flow tests;
- async scheduler tests prove bounded capacity and rejection recovery;
- processor saturation and failure paths remain bounded and do not leak permits/ownership;
- no new unbounded executor, queue, retry loop, or wall-clock-dependent microbenchmark gate is introduced;
- `mvn spotless:check` passes;
- `mvn clean verify` passes on the exact branch head;
- any defect discovered by tests is fixed in this same branch with a regression test;
- load qualification evidence is recorded separately from deterministic CI contracts.
