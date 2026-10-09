# Scheduled and background jobs

Status: **DRAFT** — implementation is mapped below; promotion to `VERIFIED` requires green exact-head verification.

## Purpose

This is the multi-replica execution contract for every Spring `@Scheduled` background process in AkmAI. A scheduler trigger is never distributed authority by itself. Durable authority must come from PostgreSQL lease/fencing, row/advisory locking, claimed work, or explicitly duplicate-safe behavior.

Accepted classifications:

1. **singleton lease/fencing** — every pod may trigger, one database-authorized owner performs work;
2. **claimed/partitioned work** — replicas may work concurrently on disjoint PostgreSQL-authorized candidates;
3. **duplicate-safe idempotent work** — duplicate execution cannot violate correctness;
4. **local telemetry/sampling** — no durable business authority is mutated.

## Inventory

| Job | Gate | Classification | Multi-pod authority | Recovery |
|---|---|---|---|---|
| `AdaptiveGraphDreamScheduler` | static Dream gate **and** authoritative runtime Dream gate; apply has its own static + runtime gate | singleton lease/fencing | PostgreSQL-time Dream lease + fencing token; fenced mutation DML | later tick resumes from durable state; stale token cannot mutate |
| `RetentionScheduler` | `akmai.retention.enabled` | claimed/partitioned | persisted claims plus lifecycle fences; stale-ingestion acquisition uses `FOR UPDATE SKIP LOCKED` | expired/rejected claims are recoverable; later ticks retry |
| `GenerationReconciliationScheduler` | `akmai.reconciliation.enabled` | candidate ownership + durable recovery | candidate-scoped transaction advisory lock; lifecycle/generation rows remain mutation authority; fresh `PURGING` tombstone is not stolen | stale `PURGING` is reclaimable; later ticks retry incomplete work |
| `ReembeddingLeaseHeartbeatScheduler` | active locally owned migrations only | duplicate-safe owner-scoped renewal | update requires matching `owner_id`, active state and live PostgreSQL-time lease | expired/taken-over ownership cannot be resurrected by stale process |
| `AdaptiveGraphMaintenanceScheduler` | static maintenance gate **and** authoritative runtime maintenance gate | claimed/partitioned | `FOR UPDATE SKIP LOCKED` acquisition inside maintenance transaction; symmetric mutation remains transactional | locked/skipped rows remain eligible for later ticks; rollback removes partial batch effects |
| `AuditPartitionMaintenanceScheduler` | always scheduled | singleton transaction ownership | `pg_try_advisory_xact_lock(hashtext('akmai.audit.partition-maintenance')::bigint)` inside the DDL transaction | rollback/connection loss releases lock; next invocation retries |
| `RetentionEconomicsSampler` | `akmai.retention-economics.enabled` | local telemetry | no durable domain mutation | next tick resamples after failure |

## Business rules

### JOB-01 — Dream trigger is not Dream authority

All replicas may invoke the scheduler. Only the current PostgreSQL lease owner with the current fencing token may continue mutation-capable Dream work. Static `false` is an immutable outer kill switch; runtime `true` cannot override it.

### JOB-02 — retention is distributed claimed work

Retention intentionally runs on every pod. Correctness comes from persisted claims and lifecycle fences, not singleton scheduling. Concurrent workers must not independently own the same cleanup candidate.

### JOB-03 — reconciliation skips busy authority non-blockingly

Reconciliation is not globally singleton. A busy candidate advisory lock is skipped rather than waited on indefinitely. Lifecycle and generation state are revalidated inside the bounded transaction before destructive repair. RETIRING keeps the durable two-phase `PURGING` recovery boundary.

### JOB-04 — heartbeat cannot resurrect lost ownership

Lease renewal must match the process owner, active migration state and still-live lease. Once a lease expires or another owner takes over, the stale process can no longer renew it.

### JOB-05 — graph maintenance is partitioned and double-gated

Maintenance runs only when both the immutable static gate and the authoritative runtime gate permit it. Candidate acquisition uses PostgreSQL locking/`SKIP LOCKED`; local scheduler overlap is not relied upon for correctness.

Symmetric graph invariants remain transactional: a failed scoring/compaction/purge batch must not commit one direction of a pair while losing the other.

### JOB-06 — audit partition DDL has one cluster owner per transaction

Before any partition DDL, the PostgreSQL function takes a non-blocking transaction advisory lock. A losing replica exits without DDL. Commit, rollback, statement failure or connection loss automatically releases ownership.

### JOB-07 — telemetry duplication is not business authority

`RetentionEconomicsSampler` reads statistics and emits metrics only. Duplicate samples may increase observability volume but cannot change publication, retention, retrieval or cleanup state.

## Positive cases

- one Dream owner acquires lease/fencing and performs work while other replicas fail acquisition;
- two retention workers process disjoint claims concurrently;
- reconciliation skips a busy candidate and later succeeds after authority is released;
- the current re-embedding owner renews a live lease;
- graph maintenance skips a locked candidate while progressing on another eligible pair;
- one audit-partition replica performs DDL while contenders return without mutation;
- multiple telemetry samplers emit observations without changing domain state.

## Negative cases

- static safety gate is `false` while runtime DB value is `true` -> no mutation-capable job starts;
- authoritative runtime flag lookup fails -> mutation-capable runtime-gated job does not invent local authority;
- Dream lease/token is stale -> mutation is rejected;
- retention/reconciliation candidate is owned elsewhere -> contender skips or remains non-authoritative;
- re-embedding lease expired or owner changed -> stale heartbeat updates zero authoritative rows;
- graph pair is locked by another transaction -> candidate is skipped/retried, not forced through JVM-local coordination;
- audit DDL advisory lock is already held -> contender performs no DDL;
- a mutating transaction fails -> PostgreSQL rollback releases transaction locks and prevents partial commit.

## Transaction and timeout semantics

- transaction-scoped PostgreSQL locks are released on commit/rollback/connection loss;
- lease/claim correctness windows use persisted database time/state rather than JVM clock authority;
- mutating jobs execute through their configured bounded transaction/JDBC contracts;
- a scheduler tick must not convert a timeout or database outage into local ownership;
- retries happen through persisted expiry/recovery state or a later scheduler invocation.

## Verification matrix

| Contract | Evidence |
|---|---|
| Dream singleton ownership/fencing | Dream lease/fencing integration tests and `dream-cycle.md` |
| Retention distributed claiming | retention claim/worker and stale-ingestion recovery integration tests |
| Reconciliation contention/recovery | `GenerationReconciliationMultipodIntegrationTest` plus reconciliation integration tests |
| Re-embedding stale owner rejection | re-embedding HA/lease integration tests |
| Graph maintenance contention/symmetry | `AdaptiveGraphMaintenanceMultipodIntegrationTest` and maintenance integration tests |
| Audit partition singleton DDL | `AuditPartitionMaintenanceIntegrationTest` |
| Telemetry duplicate safety | no durable write path in `RetentionEconomicsSampler` |

## Exit rule

This contract stays `DRAFT` until the exact branch head containing this documentation and the current implementation passes the complete required regression/CI gate set. Only then may the inventory promote eligible rows to `VERIFIED`.