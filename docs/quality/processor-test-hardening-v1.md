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

## Inventory and priority

| Area | Production component | Current evidence | Gap | Priority |
|---|---|---|---|---|
| Async ingestion dispatch | `AsyncIngestionScheduler` | worker/repository/replay tests | no direct poll/backpressure/rejection test although blueprint names one | P0 |
| Reconciliation orchestration | `GenerationReconciliationScheduler` | service multipod + observability tests | loop bound, early stop and disabled gate need direct control-flow proof | P0 |
| Retention orchestration | `RetentionScheduler` | worker-pool + observability + PostgreSQL recovery tests | disabled gate and stale-recovery batch bounds need direct proof | P0 |
| Re-embedding heartbeat | `ReembeddingLeaseHeartbeatScheduler` | HA/lease integration below scheduler | scheduled delegation/failure propagation boundary has no direct test | P0 |
| Dream trigger | `AdaptiveGraphDreamScheduler` | coordinator/lease/fencing tests | trigger boundary has no direct executable contract | P0 |
| Retention economics | `RetentionEconomicsSampler` | success metric test | disabled and sampling-failure isolation branches are shallow | P1 |
| Ingestion chunk executor | `ParallelIngestionExecutor` | bounded large-document test | saturation/failure cancellation must remain bounded | P1 |
| Generation publication | `GenerationPublicationService` / `PersistenceCoordinator` | persistence/failure-model/integration tests | verify no extra publication round-trips/side effects under replay | P1 |
| Dream coordinator | `AdaptiveGraphDreamCoordinator` | coordinator/lease/checkpoint/candidate tests | load-budget and timeout/fencing regression matrix | P1 |
| Graph maintenance | `AdaptiveGraphMaintenanceScheduler` + maintenance transaction | multipod/integration tests | sustained bounded-batch progression and rollback stress | P1 |
| Audit partition maintenance | `AuditPartitionMaintenanceScheduler` | PostgreSQL advisory-lock integration test | repeated contender stress | P2 |

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

### Retention scheduler

1. `enabled=false` performs no recovery, claim, or observation work.
2. Stale-ingestion recovery stops on the first partial batch.
3. Recovery cannot exceed `maxBatchesPerRun`.
4. The worker drain budget is exactly `batchSize * maxBatchesPerRun` and is independent of recovered-row count.
5. Existing primary-failure preservation semantics remain intact.

### Heartbeat and Dream trigger

Thin schedulers are tested as explicit contracts: exactly one delegation per trigger and no exception swallowing. Correct distributed authority remains in their already-tested lease/fencing layers.

## Performance/reliability contracts

Performance tests in this pass MUST be deterministic and CI-safe:

- assert bounded submissions and bounded claims rather than elapsed milliseconds;
- prove backpressure when all permits are occupied;
- prove capacity recovery after completion/failure/rejection;
- avoid sleeps where latches/captured tasks can prove state;
- avoid creating one executor per document/chunk;
- preserve existing shared bounded executors;
- large-input smoke tests may use generous timeouts only as hang/deadlock guards, never as microbenchmark gates.

Release/load qualification remains separate and should exercise document concurrency `1 / 3 / 5 / 8`, JDBC-pool saturation, embedding throttling, queue wait p95/p99 and multi-pod lease recovery.

## P1 follow-up

After P0 is green:

1. extend `ParallelIngestionExecutorTest` with saturation and exceptional-completion cases;
2. add publication replay call-count/side-effect regression checks;
3. add Dream budget/timeout stress tests with deterministic fake stores;
4. add maintenance batch-progression/rollback stress cases;
5. record benchmark evidence for the async worker `1 / 3 / 5 / 8` matrix.

## Exit criteria

P0 is complete only when:

- every P0 component has direct executable positive and negative/control-flow tests;
- async scheduler tests prove bounded capacity and rejection recovery;
- no new unbounded executor, queue, retry loop, or wall-clock-dependent test is introduced;
- `mvn spotless:check` passes;
- `mvn clean verify` passes on the exact branch head;
- any defect discovered by tests is fixed in this same branch with a regression test.
