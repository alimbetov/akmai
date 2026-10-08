# Post-retrieval project audit remediation specification

Status: **TARGET**  
Audit date: **2026-10-08**  
Source branch: `quality/post-retrieval-project-audit`  
Audited baseline: `main@f5aa953f0ca6bdf020e77830b14eda9e490f9955`  
Code-level execution blueprint: [`post-retrieval-project-audit-code-blueprint.md`](post-retrieval-project-audit-code-blueprint.md)

## 1. Purpose

This specification defines the single stabilization/remediation workstream after the ingestion and retrieval hardening line. It closes confirmed governance, lifecycle, multi-replica, documentation and release-qualification gaps without adding product features.

This file is the normative scope/behavior contract. The companion code blueprint is the normative implementation map for current classes, methods, SQL boundaries and tests. When code discoveries change an assumption, both documents must be updated in the same change set.

## 2. Non-goals

Out of scope:

- new retrieval strategies;
- new graph-learning algorithms;
- ANN parallelism expansion;
- new Dream scoring models;
- ranking-policy expansion;
- UI/product functionality;
- replacing PostgreSQL as distributed authority;
- reopening fixed Dream decisions without a reproduced defect;
- creating a second ownership/fencing abstraction where current row locks/leases already provide authority.

## 3. Findings and disposition

| ID | Priority | Finding | Required disposition |
|---|---|---|---|
| PRA-01 | P1 | `main` is unprotected; no required status checks | enforce PR + required checks + force-push/deletion restrictions |
| PRA-02 | P1 | PR #73 merged before exact-head CI completed | make pending/failed/cancelled checks block merge |
| PRA-03 | P1 | release qualification expects missing approved baseline | define reviewed immutable baseline process and establish only from real run |
| PRA-04 | P2 | reconciliation discovers same candidates on multiple pods | partition candidate acquisition with PostgreSQL `FOR UPDATE SKIP LOCKED`; retain lifecycle locks and two-phase tombstone durability |
| PRA-05 | P2 | current docs still contain stale #36-#41 tracker state | reconcile current docs; preserve old state only as historical snapshot |
| PRA-06 | P2 | docs synchronization SHA is stale | update only at final code/docs state before final verification |
| PRA-07 | P2 | service inventory status lags green evidence | reconcile `DRAFT/VERIFIED` on final verified SHA |
| PRA-08 | P2 | push CI omits `quality/**` and `docs/**` | add branch families without weakening PR checks |
| PRA-09 | P2 | publication/lifecycle/reembedding/graph/Dream/jobs/flags lack ingestion/retrieval-level process contracts | add code-mapped contracts and negative/concurrency tests |

## 4. Global invariants

The remediation must preserve:

1. `main` cannot accept a normal change before all required checks for the current PR head succeed.
2. Release qualification is tied to an exact immutable SHA/tag and an approved compatible baseline.
3. Publication, ACL, TTL and generation authority fail closed.
4. Optional quality/derived-memory layers may degrade but may not become authority.
5. PostgreSQL transaction/row-lock/lease/fencing state is the distributed source of truth.
6. Multi-pod workers must be singleton, claimed/partitioned, or demonstrably duplicate-safe.
7. Physical cleanup never deletes the currently published generation.
8. Background cleanup is not the retrieval-time TTL authority.
9. Runtime safety gates distinguish cached fail-safe reads from authoritative reads.
10. `VERIFIED` means current implementation + concrete positive/negative tests + green exact-head verification.
11. No final documentation-only tail commit may invalidate same-SHA verification evidence.
12. No known P0/P1 correctness defect may remain at branch exit.

---

# 5. PRA-01 / PRA-02 — default branch governance

## 5.1 Target repository rules

Protect `main` using branch protection or repository ruleset.

Required:

- pull request required for normal changes;
- required checks pending -> merge blocked;
- required check failed -> merge blocked;
- required check cancelled -> merge blocked;
- force-push disabled;
- branch deletion disabled;
- update-before-merge policy explicitly defined;
- administrator/emergency bypass, if retained, documented and not treated as release qualification.

Normal required workflow families:

- CI / verify;
- Retrieval Quality Gate;
- Retrieval Storage Final Benchmark;
- Production Image Build.

Use exact GitHub check contexts observed from successful PR checks. Do not guess names.

## 5.2 Acceptance

- the PR #73 sequence `merge -> CI completes later` cannot be repeated;
- repository metadata proves enforcement;
- production release-gates documentation records exact configured contexts and bypass policy.

---

# 6. PRA-08 — CI trigger consistency

Patch `.github/workflows/ci.yml` push branches to include:

```yaml
- "quality/**"
- "docs/**"
```

Keep `pull_request:` unchanged.

Acceptance:

- a push to this audit branch triggers normal CI;
- a `docs/**` push triggers normal CI;
- PR check remains the canonical merge-required check;
- no trigger change weakens branch protection.

---

# 7. PRA-03 — approved immutable RAG baseline

## 7.1 Contract

`rag-v1.1-release-qualification.yml` consumes:

`benchmarks/rag-benchmark-v1/baselines/approved.json`

with baseline establishment disabled. The baseline is therefore reviewed release input, not normal CI output.

## 7.2 Required process

```text
exact candidate SHA
  -> canonical production-pipeline benchmark
  -> retain raw artifacts
  -> human review corpus/result validity
  -> materialize approved.json
  -> commit approved.json with provenance
  -> compare later candidates against committed baseline
```

## 7.3 Required safety

Fail explicitly for:

- missing baseline;
- malformed/unsupported schema;
- incompatible corpus identity/fingerprint;
- incomplete/failed source run;
- mocked retrieval baseline;
- any failed quality/performance/grounding constituent job.

`qualified=true` must require every constituent release job to be successful.

## 7.4 Schema rule

Inspect the existing baseline parser before adding provenance fields. Parser/schema changes require tests in the same commit.

---

# 8. PRA-04 — multi-pod generation reconciliation

## 8.1 Confirmed current behavior

`GenerationReconciliationScheduler` runs on each pod. `GenerationReconciliationService.reconcileBatch()` currently discovers an unlocked ordered candidate list and later serializes mutation using lifecycle/generation `FOR UPDATE` locks.

The current implementation does not demonstrate concurrent duplicate destructive repair; actual mutation is serialized and state is rechecked after lock acquisition. The confirmed deficiency is duplicate discovery and avoidable blocking/prepare contention.

PRA-04 is therefore a P2 scalability/operability hardening item, not a known data-corruption defect.

## 8.2 Chosen design

Use PostgreSQL work partitioning with `FOR UPDATE SKIP LOCKED` at candidate acquisition.

Do **not** introduce a new reconciliation lease/fencing table in the first implementation because:

- repair is PostgreSQL-only and bounded;
- current lifecycle/generation row locks are already mutation authority;
- a second ownership protocol would duplicate authority without demonstrated need.

## 8.3 Required transaction model

### Terminal `RETIRED` / `FAILED`

Candidate selection must occur inside `cleanupTransactionTemplate` with deterministic order and `FOR UPDATE SKIP LOCKED`. The selected generation is revalidated and repaired under the existing database authority/timeout contract.

### `RETIRING`

Preserve the existing two-phase durability boundary:

```text
TX-A
  lock lifecycle + generation
  create/update retired-generation tombstone PURGING
COMMIT

TX-B
  re-lock lifecycle + generation
  recheck not published
  bounded physical repair
  verify residual=0
  tombstone -> PURGED
  generation -> RETIRED
COMMIT
```

Do not collapse TX-A/TX-B without a dedicated crash-recovery proof.

## 8.4 Fresh/stale PURGING behavior

Use existing tombstone state/timestamps to prevent immediate repeated preparation:

- no PURGING tombstone -> eligible;
- fresh PURGING tombstone -> skip this tick;
- stale PURGING tombstone -> recoverable/takeover path.

The stale threshold must derive from configured bounded timeout/grace semantics, not a magic SQL literal.

## 8.5 Destructive safety fence

Before every destructive phase:

```text
lock lifecycle row
if lifecycle.published_generation == candidate.generation
  -> abort cleanup for candidate
```

Never reuse a publication decision across transaction boundaries.

## 8.6 Acceptance tests

Required PostgreSQL concurrency/failure cases:

- two workers + two candidates -> disjoint progress;
- two workers + one candidate -> one processes while other skips locked discovery rather than choosing same row;
- fresh PURGING -> skipped;
- stale PURGING -> recovered;
- candidate becomes published before destructive phase -> zero delete;
- bounded repair leaves residual rows -> no false `CLEANED`;
- DB timeout/exception -> rollback + retryable state;
- successful repair -> committed lifecycle/audit final state exactly once.

Detailed class/method/SQL plan is in the code blueprint.

---

# 9. Publication / activation lifecycle

Create `docs/services/publication-lifecycle.md` and map it to `GenerationPublicationService` / `PersistenceCoordinator` / `PublicationOutcomeResolver`.

Required rules:

- publication transaction atomically persists retrieval payload, generation state, lifecycle pointer and idempotency completion;
- lifecycle row serializes publication for one document;
- active embedding profile is rechecked under lock;
- stale older generation cannot replace a newer published generation;
- previous published generation transitions to `RETIRING` exactly once or publication rolls back;
- lifecycle update failure rolls back new generation publication;
- ambiguous transaction outcome is translated only when `PublicationOutcomeResolver` proves COMMITTED/SUPERSEDED;
- semantic linking occurs after commit as derived-memory enrichment and may fail without undoing publication.

Tests must cover first publish, replacement, concurrent publication, stale/superseded publication, profile change, payload repository failure, lifecycle update failure, idempotency completion failure, ambiguous outcome and post-commit linker failure.

---

# 10. Chunk lifecycle / retention

Create `docs/services/chunk-lifecycle-retention.md`.

Required separation:

```text
synchronous published lifecycle eligibility
  = retrieval authority now

retention scheduler/worker
  = eventual physical retirement/cleanup
```

Required cases:

- expired-but-ACTIVE rows rejected synchronously;
- scheduler disabled/failing does not restore expired data eligibility;
- active retention claim prevents duplicate cleanup;
- expired claim recovers;
- worker saturation does not strand claimed work in unbounded queue;
- cleanup timeout remains retryable;
- partial cleanup cannot report success;
- current published generation is protected.

Map contract to `RetentionScheduler`, `RetentionWorkerPool`, `RetentionClaimRepository`, `ChunkRetentionService` and the canonical lifecycle eligibility reader.

---

# 11. Re-embedding ownership / fencing

Create `docs/services/reembedding.md`.

Current authority is `ReembeddingLeaseManager` over persisted:

- `owner_id`;
- `lease_until` based on PostgreSQL time;
- monotonic `fencing_token`.

Required semantics:

- new ownership requires initial unowned active migration;
- expired takeover increments token;
- renew requires matching owner/token and live lease;
- expired lease cannot be resurrected by normal renew;
- stale owner/token cannot progress/complete/fail/release after takeover;
- heartbeat only renews still-live owned active migrations;
- startup recovery does not abort healthy other-replica owner;
- `auto-migrate=false` does not weaken recovery safety;
- cutover rollback leaves old active profile authoritative.

Required multi-instance integration tests are defined in the blueprint.

---

# 12. Repair / reconciliation

Create `docs/services/generation-reconciliation.md`.

Beyond PRA-04 document/test:

- orphan retrieval payload cleanup;
- batch limit exhaustion;
- residual rows after repair;
- missing embedding profile;
- tombstone state mismatch;
- audit event accuracy;
- retry after rollback/partial prior committed phase;
- cleanup/repair transaction timeout.

Audit success/deferred events must represent committed lifecycle state, not optimistic intent.

---

# 13. Adaptive graph mutation

Create `docs/services/adaptive-graph-mutation.md`.

`GraphMutationLocks` remains the canonical shared mutation primitive for:

- `AdaptiveChunkGraphRepository`;
- `SemanticAssociationSeedRepository`;
- `SemanticGraphPriorWriter`.

Required invariants:

- canonical pair ordering before locks;
- lifecycle eligibility locked before mutation;
- online/semantic policy differences remain explicit;
- bilateral mutation atomic;
- reversed pair concurrency no deadlock;
- configured JDBC/transaction timeout bounds lock wait;
- half-pair failure rolls back whole pair;
- Dream prior apply additionally requires valid fencing authority in guarded DML.

Do not duplicate lifecycle/advisory lock SQL back into individual writers.

---

# 14. Dream ownership / candidate lifecycle

Create `docs/services/dream-cycle.md` and only split candidate lifecycle into a separate contract if it is independently useful.

Fixed decisions:

- fast lane accelerates change discovery but is not completeness guarantee;
- bounded rescan is eventual coverage guarantee;
- one serial Dream owner;
- PostgreSQL-time lease + fencing authority;
- static config + runtime DB flags form double gate;
- ACTIVE candidate is current semantic state;
- DREAM-4B `maxNewEdgesPerChunk` is per-source per-run ACTIVE admission limit;
- bilateral graph degree admission remains separate;
- concurrency remains 1;
- JDBC/ANN calls require real resource timeouts.

Map to:

- `AdaptiveGraphDreamScheduler`;
- `AdaptiveGraphDreamCoordinator.runOnce()`;
- `DreamLeaseManager`;
- `DreamLeaseHeartbeat`;
- checkpoint/rescan repositories;
- candidate discovery/repository;
- `SemanticGraphPriorWriter`.

Required negative tests include lease loss mid-run, checkpoint resume, budget stop, malformed cursor, stale token, candidate deactivation/retention transition, query timeout and transaction timeout.

---

# 15. Runtime feature flags / safety gates

Create `docs/services/runtime-safety-flags.md`.

`AppParameterService` currently has two important read contracts:

```text
get(key)
  cache -> repository -> lastKnownGood -> static fallback

getAuthoritative(key)
  repository only -> explicit unavailable on DB failure
```

For every `AppParameterKey`, document:

- static fallback;
- dependency transitions;
- consumers;
- cached/fail-safe vs authoritative read requirement;
- consequence of DB unavailability/staleness.

Required tests:

- repository failure with LKG;
- repository failure without LKG;
- authoritative failure never falls back;
- optimistic conflict does not poison cache;
- rolled-back update does not publish cache value;
- stale cached `true` cannot authorize a mutation path defined to require authoritative state;
- concurrent dependency transitions remain atomic.

---

# 16. Scheduled/background jobs

Create `docs/services/scheduled-jobs.md` and classify every `@Scheduled` component as:

- singleton lease/fencing;
- claimed/partitioned work;
- duplicate-safe idempotent work;
- local telemetry/sampling.

At minimum classify:

- `AdaptiveGraphDreamScheduler`;
- `RetentionScheduler`;
- `GenerationReconciliationScheduler`;
- `ReembeddingLeaseHeartbeatScheduler`;
- `AdaptiveGraphMaintenanceScheduler`;
- `AuditPartitionMaintenanceScheduler`;
- `RetentionEconomicsSampler`.

For every job document trigger, gates, multi-pod authority, transaction boundary, timeout, retry/recovery and metrics.

Any job with neither singleton/claim semantics nor proven idempotent duplicate safety is a new audit finding and must be fixed or explicitly retained as bounded debt.

---

# 17. Documentation truthfulness

## 17.1 #36-#41

Current documentation must reflect actual closed/completed tracker state. Historical snapshots may preserve old state only when explicitly tied to old SHA/date.

## 17.2 `docs/README.md`

Update `Last synchronized against:` only after final code/docs state is known and before final verification.

## 17.3 Service inventory

Review independently:

- `knowledge-ingestion.md`;
- `retrieval-flow.md`;
- `retrieval-routing.md`;
- `retrieval-execution.md`;
- `retrieval-selection.md`;
- all new contracts introduced by this branch.

Promotion rule:

```text
CURRENT code match
+ concrete positive/negative tests
+ final exact-head green verification
+ no material unresolved correctness gap
= VERIFIED
```

Otherwise remain `DRAFT` with explicit missing evidence/gap.

---

# 18. Required implementation order

1. patch CI branch coverage;
2. reconcile stale current documentation claims;
3. implement/test PRA-04 reconciliation partitioning;
4. publication contract + missing tests;
5. retention contract + missing tests;
6. re-embedding contract + missing tests;
7. reconciliation/repair contract completion;
8. graph mutation contract/test mapping;
9. Dream cycle/candidate contract/test mapping;
10. runtime flag contract/tests;
11. scheduled-job classification and any discovered multi-pod fixes;
12. baseline parser/process hardening; establish real `approved.json` only after reviewed live run;
13. reconcile service inventory;
14. set final docs synchronization SHA;
15. run final exact-head normal CI/quality/storage/image checks;
16. configure/verify `main` branch protection with confirmed check contexts;
17. run integrated release qualification for intended candidate/tag once baseline/external dependencies are available.

A reproduced P1 defect may interrupt this order and must be fixed first in the same branch.

---

# 19. Definition of Done

## Governance / CI

- [ ] `main` protected by PR and exact required checks.
- [ ] pending/failed/cancelled checks block merge.
- [ ] force push/deletion policy enforced.
- [ ] `quality/**` push triggers CI.
- [ ] `docs/**` push triggers CI.

## Reconciliation

- [ ] candidate acquisition uses PostgreSQL `FOR UPDATE SKIP LOCKED` or a documented equivalent justified by failing tests.
- [ ] current publication is rechecked before destructive cleanup.
- [ ] RETIRING tombstone durability remains two-phase.
- [ ] fresh PURGING work is not immediately reclaimed.
- [ ] stale PURGING work is recoverable.
- [ ] residual rows block false completion.
- [ ] multi-pod integration tests green.

## Publication / lifecycle

- [ ] atomic publication behavior documented and tested.
- [ ] concurrent/stale/ambiguous publication tested.
- [ ] TTL eligibility remains synchronous and independent of retention scheduler.
- [ ] retention claim/retry/saturation/timeout cases documented and tested.

## Re-embedding

- [ ] owner/lease/fencing contract documented.
- [ ] stale-owner takeover and heartbeat behavior tested.
- [ ] startup recovery cannot abort healthy owner.

## Graph / Dream

- [ ] shared graph locking remains canonical.
- [ ] reversed-pair, timeout, rollback and stale-fencing tests mapped/green.
- [ ] Dream fast/rescan/lease/budget/checkpoint/candidate semantics documented and tested.

## Runtime flags / jobs

- [ ] every app parameter has cached vs authoritative semantics documented.
- [ ] every scheduled job has multi-pod classification.
- [ ] uncovered unsafe job semantics are fixed or explicitly recorded as bounded debt.

## Documentation

- [ ] current docs no longer claim closed #36-#41 are open.
- [ ] final docs synchronization SHA matches final branch state.
- [ ] inventory statuses match final evidence.
- [ ] no unverified documentation tail commit after final green SHA.

## Release qualification

- [ ] approved baseline parser/process is deterministic and tested.
- [ ] formal `approved.json` derives from reviewed real pipeline run.
- [ ] failed constituent job cannot yield `qualified=true`.

## Exit

- [ ] final exact PR head passes formatting + `mvn -B clean verify`.
- [ ] final exact PR head passes Retrieval Quality Gate.
- [ ] final exact PR head passes Retrieval Storage Final Benchmark.
- [ ] final exact PR head passes Production Image Build.
- [ ] no known P0/P1 correctness defect remains in this scope.
- [ ] remaining P2/P3 debt is explicit and does not contradict readiness claims.
