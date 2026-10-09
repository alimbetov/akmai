# Scheduled and background jobs

Status: **DRAFT**

## Purpose

This document is the multi-replica execution contract for every Spring `@Scheduled` component in AkmAI. A scheduler trigger is never distributed authority by itself: every mutating job must be protected by PostgreSQL lease/fencing, claimed/partitioned work, or an explicitly proven duplicate-safe operation.

The four accepted classifications are:

1. **singleton lease/fencing** — every pod may trigger, but only one database-authorized owner may perform the work;
2. **claimed/partitioned work** — every pod may work concurrently, but PostgreSQL assigns disjoint candidates non-blockingly;
3. **duplicate-safe idempotent work** — duplicate execution is bounded and cannot violate correctness;
4. **local telemetry/sampling** — no durable business state is mutated; duplicate samples only affect observability cost/volume.

## Inventory

| Job | Trigger | Gates | Classification | Multi-pod authority | Transaction / timeout boundary | Retry / recovery | Metrics / observability |
|---|---|---|---|---|---|---|---|
| `AdaptiveGraphDreamScheduler` | cron `akmai.adaptive-graph.dream.cron`, configured zone | static Dream config + authoritative runtime Dream gate; apply has an independent double gate | singleton lease/fencing | `AdaptiveGraphDreamCoordinator` acquires PostgreSQL-time Dream lease; fencing token guards mutations; local overlap is also rejected | bounded Dream DB transactions plus configured JDBC/ANN call timeouts | later tick may resume from durable checkpoint/rescan state; stale token cannot mutate | Dream run/phase/lease metrics and structured logs |
| `RetentionScheduler` | cron `akmai.retention.cron`, configured zone | `akmai.retention.enabled` | claimed/partitioned work | `RetentionClaimRepository.claimExpired(...)` claims bounded work; stale-ingestion recovery uses `FOR UPDATE SKIP LOCKED` | claim and cleanup transactions are bounded by retention configuration; worker pool capacity is bounded | expired claims recover; retry limit is persisted; rejected submissions release claims | retention run/backlog/claimed/recovery metrics and logs |
| `GenerationReconciliationScheduler` | fixed delay `akmai.reconciliation.fixed-delay` | `akmai.reconciliation.enabled` | claimed/partitioned work | candidate-scoped PostgreSQL transaction advisory lock skips a busy candidate; lifecycle/generation rows remain correctness authority | bounded cleanup and `REQUIRES_NEW` repair transactions with configured timeouts | stale PURGING takeover and later scheduler ticks retry durable incomplete work | reconciliation run/audit/log evidence |
| `ReembeddingLeaseHeartbeatScheduler` | fixed delay `akmai.reembedding.heartbeat-interval` | no separate scheduler flag; only locally owned active migrations are eligible | duplicate-safe owner-scoped lease maintenance | `renewOwnedActiveLeases()` updates only rows with this process `owner_id`, active status and a still-live PostgreSQL-time lease | one bounded JDBC update per tick; lease duration defines correctness window | an expired lease is not resurrected; takeover changes owner/token and stale process renewal stops matching | ownership/re-embedding logs and migration state; no scheduler-specific counter required for authority |
| `AdaptiveGraphMaintenanceScheduler` | fixed delay `akmai.adaptive-graph.maintenance.fixed-delay` | static graph config fallback only when service is absent in tests; production path uses authoritative runtime maintenance gate | claimed/partitioned work | scoring, compaction and purge acquisition use PostgreSQL `FOR UPDATE SKIP LOCKED` inside a transaction; symmetric pair invariant remains atomic | one transaction per maintenance batch; bounded batch count per run | skipped locked rows remain eligible for a later batch/tick; failed transaction rolls back | adaptive graph maintenance and band transition metrics + logs |
| `AuditPartitionMaintenanceScheduler` | application-ready event and cron `akmai.audit.partition-maintenance-cron` | always enabled | singleton PostgreSQL transaction lock | `akmai_admin.maintain_audit_partitions()` first executes `pg_try_advisory_xact_lock(hashtext('akmai.audit.partition-maintenance')::bigint)`; losing replicas return without DDL | one PostgreSQL statement/transaction owns the transaction-scoped advisory lock and all partition DDL; transaction completion releases authority automatically | a skipped replica does nothing; a failed owner rolls back/relinquishes lock and the next startup/cron invocation retries | failure is surfaced by Spring/JDBC; partition existence is the durable result. Dedicated job metric is currently not required for correctness |
| `RetentionEconomicsSampler` | fixed delay `akmai.retention-economics.fixed-delay` | `akmai.retention-economics.enabled` | local telemetry/sampling | none required: service reads storage statistics and publishes metrics only; no authoritative domain state is written | bounded read calls; exceptions are caught by sampler | next tick resamples; failures do not change retention authority | retention economics/store/tombstone metrics + warning log |

## Job-specific invariants

### JOB-01 — Dream trigger is not Dream authority

Every replica may execute `AdaptiveGraphDreamScheduler.runScheduled()`. Only the coordinator that holds the current PostgreSQL lease/fencing authority may continue into Dream work. Scheduler multiplicity must never be converted into multiple active Dream owners.

### JOB-02 — retention is distributed claimed work

`RetentionScheduler` intentionally runs on every pod. Correctness is provided by database claims and lifecycle fences, not by electing one scheduler pod.

The stale-ingestion recovery sub-path is also partitioned: `DocumentGenerationRepository.failStaleIngestionBatch(...)` selects candidates with `FOR UPDATE SKIP LOCKED`, so concurrent schedulers cannot recover the same staging generation as independent work.

### JOB-03 — reconciliation skips busy candidates

Reconciliation is not globally singleton. A worker must skip a candidate whose transaction advisory key is already held, then continue bounded progress on other candidates. Publication safety still depends on lifecycle/generation row revalidation after candidate ownership is obtained.

### JOB-04 — heartbeat cannot resurrect lost ownership

`ReembeddingLeaseHeartbeatScheduler` is safe on every pod because each manager has a process-local random `owner_id`. Bulk renewal requires that exact `owner_id`, active migration status, and `lease_until > clock_timestamp()`. After expiration/takeover the stale process no longer matches the row and cannot renew it.

### JOB-05 — graph maintenance is work-partitioned, not singleton

`AdaptiveGraphMaintenanceService` claims due scoring rows, compaction rows, and expired DECAYED rows with `FOR UPDATE SKIP LOCKED` inside the maintenance transaction. Two pods therefore may progress concurrently on disjoint graph rows.

Required evidence:

- functional scoring/decay/compaction tests;
- `AdaptiveGraphMaintenanceMultipodIntegrationTest.lockedCandidateIsSkippedWhileAnotherPairMakesProgress` proves that one locked canonical pair does not block progress on another pair and remains retryable after lock release.

### JOB-06 — audit partition DDL has one cluster owner per transaction

Before this hardening, every startup and cron tick could concurrently execute `CREATE TABLE/INDEX` and `DROP TABLE` DDL. `CREATE ... IF NOT EXISTS` was not an ownership protocol, and concurrent drops were not proven safe.

The repaired function obtains a non-blocking PostgreSQL transaction advisory lock before any DDL. The losing replica returns immediately. The lock is transaction-scoped, so commit, rollback, connection loss, and statement failure cannot strand ownership.

Required evidence:

- `AuditPartitionMaintenanceIntegrationTest.concurrentReplicaSkipsPartitionDdlWhileAuthorityIsHeld` holds the same advisory key on one connection, proves a contender performs no partition creation, releases the lock, and proves a later invocation creates the missing partition.

### JOB-07 — telemetry may duplicate without becoming authority

`RetentionEconomicsSampler` may run on every pod. It performs observation-only reads and emits metrics. Multiple replicas may therefore publish duplicate samples, which can increase metric volume but cannot mutate lifecycle, retention, publication, or retrieval authority.

## Failure semantics

- **Database unavailable:** mutating jobs fail or skip according to their explicit ownership/gate contract; they must not invent local authority.
- **Owner transaction rolls back:** PostgreSQL transaction locks are released and durable mutation is rolled back.
- **Pod dies:** transaction locks release with connection loss; leases/claims recover through persisted expiry semantics.
- **Scheduler overlap on one pod:** correctness must still come from the same database authority used across pods; JVM scheduling behavior is not relied upon as a safety fence.
- **Metrics failure:** observability must not become domain authority. Jobs whose metric calls are inside `finally`/post-run paths retain their existing failure semantics.

## Verification matrix

| Contract | Evidence |
|---|---|
| Dream singleton ownership | Dream lease/fencing integration tests and Dream service contract |
| Retention distributed claiming | retention claim/worker and stale-ingestion recovery integration tests |
| Reconciliation busy-candidate skip | `GenerationReconciliationMultipodIntegrationTest` |
| Re-embedding stale owner cannot renew | re-embedding HA/lease integration tests |
| Graph maintenance contention | `AdaptiveGraphMaintenanceMultipodIntegrationTest` |
| Audit partition singleton DDL | `AuditPartitionMaintenanceIntegrationTest` |
| Telemetry duplicate safety | `RetentionEconomicsSampler` has no durable write path |

## Exit rule

This contract remains **DRAFT** until the exact branch head containing the migration, contention tests, and this document passes the normal verification workflow. After that evidence exists, it may be promoted to **VERIFIED** without changing the execution semantics described here.
