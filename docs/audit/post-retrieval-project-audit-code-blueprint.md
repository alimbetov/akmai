# Post-retrieval project audit — code-level implementation blueprint

Status: **TARGET / EXECUTION BLUEPRINT**  
Branch: `quality/post-retrieval-project-audit`  
Baseline: `main@f5aa953f0ca6bdf020e77830b14eda9e490f9955`  
Parent specification: [`post-retrieval-project-audit-remediation-spec.md`](post-retrieval-project-audit-remediation-spec.md)

## 1. Purpose

This document turns the post-retrieval audit specification into an implementation-level work plan. It is intentionally concrete: every work item is mapped to current classes, methods, SQL boundaries, test surfaces, documentation artifacts and verification evidence.

The goal is to avoid design drift while implementing the remediation. If code differs from this blueprint during execution, the blueprint must be updated in the same commit or the deviation must be explained in the PR.

## 2. Global implementation rules

1. Do not introduce new product features.
2. Preserve existing retrieval, ingestion, graph and Dream semantics unless a failing negative test demonstrates a correctness defect.
3. Prefer extending existing ownership/fencing/lifecycle primitives over creating competing abstractions.
4. PostgreSQL remains the distributed authority for publication, lifecycle, retention, re-embedding and Dream ownership.
5. Expensive or external work must not be hidden inside a transaction unless the current implementation already proves the operation is database-only and bounded by a configured transaction timeout.
6. Every mutation path must have a negative/failure test before production behavior is changed.
7. Every background job must be classified explicitly as one of:
   - singleton via lease/fencing;
   - partitioned/claimed work;
   - idempotent duplicate-safe work;
   - local-only telemetry/sampling.
8. `DRAFT -> VERIFIED` documentation promotion occurs only on a final SHA that itself passed the required checks.
9. A successful prior SHA cannot be used as evidence for an untested tail commit.

---

# 3. Code map

| Area | Current code boundary | Primary tests to extend/create | Target service contract |
|---|---|---|---|
| Publication | `knowledge/ingestion/GenerationPublicationService` | `ConcurrentGenerationPublicationIntegrationTest`, `PostgresVectorPublicationIntegrationTest`, `PublicationContractTest` | `docs/services/publication-lifecycle.md` |
| Ingestion publication orchestration | `knowledge/ingestion/PersistenceCoordinator`, `PublicationOutcomeResolver` | `PersistenceCoordinatorTest`, `PersistenceCoordinatorFailureModelTest` | existing ingestion + publication contract |
| Reconciliation | `knowledge/lifecycle/GenerationReconciliationScheduler`, `GenerationReconciliationService` | new `GenerationReconciliationConcurrencyIntegrationTest`; extend existing reconciliation tests | `docs/services/generation-reconciliation.md` |
| Physical repair | `knowledge/lifecycle/GenerationRepairService` | repair failure/batch tests | reconciliation contract |
| Retention | `RetentionScheduler`, `RetentionWorkerPool`, `RetentionClaimRepository`, `ChunkRetentionService` | `RetentionWorkerPoolTest`, `RetentionSchedulerObservabilityTest`, PostgreSQL lifecycle tests | `docs/services/chunk-lifecycle-retention.md` |
| Re-embedding HA | `ReembeddingService`, `ReembeddingLeaseManager`, `ReembeddingLeaseHeartbeatScheduler`, `ReembeddingStartupRunner` | existing re-embedding HA/concurrency tests + new service contract tests | `docs/services/reembedding.md` |
| Runtime flags | `runtimeconfig/AppParameterService`, `AppParameterRepository`, `AppParameterController` | app-parameter transition/cache/failure tests | `docs/services/runtime-safety-flags.md` |
| Graph mutation | `GraphMutationLocks`, `AdaptiveChunkGraphRepository`, `SemanticAssociationSeedRepository` | graph concurrency/failure-injection tests | `docs/services/adaptive-graph-mutation.md` |
| Dream prior apply | `dream/SemanticGraphPriorWriter` | stale-token, reversed pair, timeout tests | graph/Dream contracts |
| Dream ownership/orchestration | `AdaptiveGraphDreamScheduler`, `AdaptiveGraphDreamCoordinator`, `DreamLeaseManager`, `DreamLeaseHeartbeat` | coordinator/lease/checkpoint/candidate tests | `docs/services/dream-cycle.md` |
| CI normal gate | `.github/workflows/ci.yml`, retrieval/storage/image workflows | workflow trigger verification | operations release-gates docs |
| Release qualification | `.github/workflows/rag-v1.1-release-qualification.yml`, benchmark tooling | release-quality parser/runner tests | release-gates + benchmark docs |
| Documentation status | `docs/services/service-inventory.md`, `docs/README.md`, `docs/audit/*` | grep/review + CI | current docs only |

---

# 4. PRA-01 / PRA-02 — branch governance and merge gating

## 4.1 Repository-side change

This is not a Java code change. Configure `main` using branch protection or a repository ruleset.

Required behavior:

```text
pull request opened
  -> required checks pending
      -> merge blocked
  -> any required check failed/cancelled
      -> merge blocked
  -> all required checks success for current head SHA
      -> merge may proceed
```

Required normal check contexts must be copied from a successful current PR. Do not hard-code guessed names in documentation before confirming GitHub's exact contexts.

Expected workflow families:

- CI / verify;
- Retrieval Quality Gate;
- Retrieval Storage Final Benchmark;
- Production Image Build.

## 4.2 `.github/workflows/ci.yml`

Current push branches do not include the branch families used by this stabilization line.

Required patch:

```yaml
on:
  push:
    branches:
      - main
      - "feature/**"
      - "fix/**"
      - "audit/**"
      - "quality/**"
      - "docs/**"
  pull_request:
```

Do not remove `pull_request:`.

## 4.3 Workflow verification

Verify:

1. push to `quality/post-retrieval-project-audit` creates CI run;
2. PR event still creates the canonical required CI check;
3. if push + PR cause two runs, required context still resolves to the PR check intended by protection rules;
4. branch protection blocks merge while the PR check is pending.

## 4.4 Documentation

Update `docs/operations/production-release-gates.md` with:

- actual protected branch/ruleset state;
- exact required check contexts;
- whether branch must be current with `main` before merge;
- emergency bypass policy;
- rule that bypassed SHA is not a quality-stable baseline until requalified.

---

# 5. PRA-03 — immutable approved RAG baseline

## 5.1 Current workflow contract

`.github/workflows/rag-v1.1-release-qualification.yml` calls the release quality workflow with:

```text
baseline_file = benchmarks/rag-benchmark-v1/baselines/approved.json
establish_baseline = false
```

Therefore `approved.json` is an input artifact, not an automatically generated release side effect.

## 5.2 Required implementation work

Before creating `approved.json`, inspect the parser/consumer in the benchmark/release-quality code and document its exact accepted schema. Add parser tests before extending the schema.

Required provenance fields, only if supported by the parser or added with tests:

- `schemaVersion`;
- source git SHA;
- corpus identifier/fingerprint;
- benchmark contract/runner version;
- metric values used by gate comparison;
- establishment timestamp;
- human review note or source run identifier.

## 5.3 File lifecycle

```text
benchmark run
  -> raw result artifact
  -> human review
  -> materialize approved.json
  -> commit approved.json
  -> run release qualification against committed file
```

Forbidden:

- auto-overwriting `approved.json` from normal CI;
- approving a mocked-retrieval result;
- comparing a candidate against an incompatible corpus version without explicit migration.

## 5.4 Tests

Add/extend tests for:

- missing baseline -> explicit failure;
- malformed JSON/schema -> explicit failure;
- incompatible corpus fingerprint -> explicit failure or explicit migration path;
- constituent release job failure -> final `qualified=false` and workflow failure;
- all constituent jobs success -> final `qualified=true`.

---

# 6. PRA-04 — generation reconciliation: resolved implementation design

## 6.1 Current code

### Scheduler

`GenerationReconciliationScheduler.reconcile()`:

- executes on every pod via `@Scheduled`;
- loops up to `properties.maxBatchesPerRun()`;
- calls `GenerationReconciliationService.reconcileBatch()`;
- emits aggregate run metrics.

### Candidate discovery

`GenerationReconciliationService.reconcileBatch()` currently:

1. executes a normal ordered `SELECT ... LIMIT ?` over `knowledge_document_generation`;
2. iterates candidate keys;
3. enters `cleanupTransactionTemplate` per candidate;
4. locks lifecycle and generation rows before destructive mutation.

### Physical repair

`GenerationRepairService.repair()` is database-only. Each pass deletes bounded rows from PostgreSQL-backed retrieval tables; there is no HTTP/model call in this method. `repairTransactionTemplate` therefore remains within the database transaction domain.

## 6.2 Audit correction

The current implementation does **not** prove concurrent duplicate destructive repair. Lifecycle/generation `FOR UPDATE` locks serialize actual mutation, and a second worker rechecks generation state after the first worker commits.

The confirmed gap is narrower:

- duplicate candidate discovery across pods;
- avoidable blocking/lock contention;
- duplicate prepare/probe work around `RETIRING` generations;
- no explicit `SKIP LOCKED` partitioning at candidate acquisition.

Treat PRA-04 as a scalability/operability hardening item, not as a known corruption bug.

## 6.3 Chosen design

Use PostgreSQL row-level work partitioning with `FOR UPDATE SKIP LOCKED` and keep the existing generation/lifecycle rows as authority. **Do not add a new reconciliation lease table in the first implementation.**

Reason:

- repair is database-only and bounded;
- existing row locks already define mutation authority;
- adding a second lease/fencing model would duplicate lifecycle authority without evidence that it is necessary.

## 6.4 Refactor shape

Refactor `GenerationReconciliationService` into explicit operations:

```java
int reconcileBatch();

private Optional<GenerationKey> selectNextCandidateForUpdate();
private ReconcileAction prepareCandidate(GenerationKey key);
private void purgePreparedRetiring(GenerationKey key);
private boolean reconcileTerminal(GenerationKey key);
```

Exact names may differ, but responsibilities must be separated.

### Candidate selection SQL

Candidate selection must execute inside `cleanupTransactionTemplate` and include:

```sql
ORDER BY ...
FOR UPDATE SKIP LOCKED
LIMIT 1
```

Process candidates one-at-a-time per cleanup transaction rather than selecting an unlocked list up front.

The transaction that selects a terminal `RETIRED`/`FAILED` row may continue through `reconcileTerminal()` because repair is PostgreSQL-only and bounded by the cleanup/repair transaction contracts.

## 6.5 `RETIRING` two-phase preservation

Do **not** collapse the current `prepareRetiring()` and `purgeRetiring()` durability boundary without a dedicated crash-recovery proof.

Current two-phase intent must be preserved:

```text
transaction A
  lifecycle + generation lock
  -> write/update knowledge_retired_generation tombstone PURGING
COMMIT

transaction B
  lifecycle + generation lock again
  -> physical repair/delete
  -> verify residual=0
  -> tombstone PURGED
  -> generation RETIRED
COMMIT
```

This ensures a durable tombstone exists before physical payload deletion.

## 6.6 Fresh PURGING tombstone suppression

To reduce duplicate prepare contention, candidate discovery for `RETIRING` should avoid immediately reclaiming a generation with a fresh `knowledge_retired_generation.cleanup_status='PURGING'` tombstone.

Use existing `purge_started_at` / cleanup state if sufficient. Preferred logic:

```text
RETIRING with no PURGING tombstone
  -> eligible

RETIRING with fresh PURGING tombstone
  -> skip this tick

RETIRING with stale PURGING tombstone
  -> eligible for crash recovery
```

The stale threshold must be derived from an existing bounded timeout/grace configuration where practical; do not add a magic literal in SQL.

When taking over a stale `PURGING` tombstone, update `purge_started_at`, increment `cleanup_attempts`, clear `last_error`, then proceed.

## 6.7 Publication fence

Every destructive phase must keep the existing fail-closed check:

```text
lock knowledge_document_lifecycle
if published_generation == candidate generation
  -> do not delete
```

No optimization may cache this decision across transactions.

## 6.8 `GenerationRepairService`

Keep its batch delete model unless tests expose a defect.

Required checks:

- `maxBatchesPerRun` bounds work;
- every table delete is generation + ACL scoped;
- association delete remains bidirectional-safe;
- transaction timeout is effective at JDBC boundary;
- zero residual rows are required before lifecycle finalization.

Do not move repair work outside the lifecycle/generation authority transaction without adding an equivalent fencing check to every delete pass.

## 6.9 Tests

Create `GenerationReconciliationConcurrencyIntegrationTest` using PostgreSQL/Testcontainers where existing integration-test infrastructure permits.

Required scenarios:

1. two workers, two different terminal generations -> both can progress without blocking on the same candidate;
2. two workers, one terminal generation -> one locks/processes, the other skips rather than waits on the same discovery row;
3. `RETIRING` with fresh `PURGING` tombstone -> skipped;
4. stale `PURGING` tombstone -> recoverable;
5. candidate becomes published before destructive phase -> no physical delete;
6. repair residual remains after bounded batches -> generation not `CLEANED`, `last_error` set, audit says deferred;
7. DB timeout/exception -> rollback, generation remains retryable;
8. successful terminal repair -> `CLEANED`, `cleanup_required=false`, audit exactly once at committed lifecycle level.

Unit tests must also verify scheduler metrics distinguish processed/skipped/deferred/failed where metrics API supports it.

---

# 7. Publication lifecycle code contract

## 7.1 Current owner

`GenerationPublicationService.publish(...)` owns the publication transaction through `publicationTransactionTemplate`.

Inside `publishInTransaction(...)` it currently:

1. locks idempotency claim when present;
2. locks `knowledge_embedding_runtime` and verifies active embedding profile;
3. locks `knowledge_document_lifecycle` by `document_id`;
4. locks target `knowledge_document_generation`;
5. rejects invalid generation state/profile;
6. marks older target as `SUPERSEDED` when a newer generation is already published;
7. persists projection/identifier/reference/vector-generation/vector payload;
8. transitions previous published generation to `RETIRING`;
9. transitions target generation `STAGING -> PUBLISHED`;
10. updates lifecycle publication pointer + retention fields;
11. completes idempotency in the same transaction;
12. performs semantic linking after commit, fail-open as a derived-memory enhancement.

## 7.2 Required invariants to document/test

### PUB-01 Atomic publication

Projection, identifiers, references, vector manifest/vector rows, generation state, lifecycle pointer and idempotency completion must commit or roll back together.

### PUB-02 Publication ordering

The lifecycle row is the document-level serialization fence. Concurrent publications for one document must not create two active published generations.

### PUB-03 Older generation

If `previous > generation`, target becomes `FAILED/SUPERSEDED`; it must not overwrite lifecycle publication.

### PUB-04 Already published

Target `generation_status='PUBLISHED'` returns `ALREADY_PUBLISHED` without rewriting staged payload as a new publication.

### PUB-05 Active embedding profile

The generation's profile and corpus active profile must match under lock at publication time.

### PUB-06 ACL identity

`knowledge_document_generation.access_level` is the authority for `GenerationIdentity`; payload supplied to repositories must not silently mix another ACL.

### PUB-07 Previous generation retirement

If a previous generation exists and differs from target, exactly one row must transition `PUBLISHED -> RETIRING`; otherwise rollback.

### PUB-08 Ambiguous transaction outcome

If `transactionTemplate.execute` throws, `PublicationOutcomeResolver.resolve(...)` may translate only proven `COMMITTED` or `SUPERSEDED` outcomes. Unknown/uncommitted state rethrows original failure.

### PUB-09 Post-commit semantic linking

`IngestionSemanticLinker.linkPublishedGeneration(...)` is not part of publication authority. Its failure is logged and must not roll back an already published generation.

## 7.3 Required test matrix

Extend/use:

- `ConcurrentGenerationPublicationIntegrationTest`;
- `PostgresVectorPublicationIntegrationTest`;
- `PublicationContractTest`;
- `PersistenceCoordinatorFailureModelTest`.

Scenarios:

- first publication;
- replacement publication;
- two concurrent generations, higher wins according to serialization/current state;
- stale older publication returns `SUPERSEDED`;
- active embedding profile changes before publication -> rollback;
- failure in each payload repository -> no lifecycle pointer change;
- failure transitioning previous generation -> rollback new payload/publication;
- failure updating lifecycle pointer -> rollback generation `PUBLISHED` transition;
- idempotency completion failure -> entire publication rollback;
- ambiguous exception + resolver COMMITTED -> return `PUBLISHED`;
- ambiguous exception + resolver SUPERSEDED -> return `SUPERSEDED`;
- ambiguous exception + unresolved -> propagate;
- semantic linker failure after commit -> publication remains visible and valid.

Create `docs/services/publication-lifecycle.md` and link it from ingestion rather than duplicating all rules in `knowledge-ingestion.md`.

---

# 8. Retention/lifecycle code contract

## 8.1 Current owners

- `RetentionScheduler` — scheduler/run boundary;
- `RetentionWorkerPool` — local bounded worker capacity + claimed jobs;
- `RetentionClaimRepository` — persisted claim authority;
- `ChunkRetentionService` — cleanup behavior;
- published lifecycle eligibility abstraction — synchronous read-time TTL/visibility fence.

## 8.2 Mandatory separation

```text
synchronous eligibility
  -> decides whether data may be retrieved NOW

retention scheduler/worker
  -> eventually removes/retire physical data
```

Scheduler health must never be required for TTL correctness.

## 8.3 Test matrix

- expired but still `ACTIVE` row -> all retrieval lanes/final revalidation reject it;
- retention disabled -> synchronous expiry still rejects retrieval;
- claim lease active -> second worker cannot claim same cleanup;
- claim lease expired -> another worker can recover;
- worker pool saturated -> no claimed item is stranded waiting in an unbounded executor queue;
- cleanup transaction timeout -> retryable row state retained;
- partial cleanup -> not falsely marked complete;
- scheduler exception -> run outcome metric FAILED;
- deleted/current/published generation protection remains fail-closed.

Create `docs/services/chunk-lifecycle-retention.md`.

---

# 9. Re-embedding code contract

## 9.1 Current authority

`ReembeddingLeaseManager` already persists ownership in `knowledge_embedding_migration` using:

- `owner_id`;
- `lease_until` using `clock_timestamp()`;
- monotonic `fencing_token`.

Important current methods:

```java
Authority claimNew(UUID migrationId);
Optional<Authority> claimExpiredActive();
void renew(Authority authority);
int renewOwnedActiveLeases();
boolean isOwned(Authority authority);
```

## 9.2 Required invariants

- `claimNew` only claims an unowned, initial-token active migration;
- expired takeover increments fencing token;
- `renew` requires same owner + token + live lease;
- expired lease cannot be resurrected by normal renew;
- stale owner/token cannot progress, complete, fail or release after takeover;
- heartbeat scheduler only renews still-live owned active migrations;
- startup recovery cannot abort a healthy other-replica owner;
- `auto-migrate=false` must not weaken ownership safety.

## 9.3 Tests

Create/extend integration tests proving:

1. token monotonicity on takeover;
2. A owns migration, B starts -> B does not recover/abort A;
3. A lease expires, B claims -> A cannot mutate with stale token;
4. B renews and finishes normally;
5. heartbeat fails/DB unavailable -> lease eventually expires; no local fiction of authority;
6. restart with expired owner -> recovery proceeds;
7. cutover transaction rollback preserves old active profile and migration retryability.

Create `docs/services/reembedding.md`.

---

# 10. Runtime app-parameter safety contract

## 10.1 Current code

`AppParameterService` has two read modes:

```text
get(key)
  -> cache
  -> repository
  -> lastKnownGood
  -> static fallback

getAuthoritative(key)
  -> repository only
  -> DataAccessException => AppParameterUnavailableException
```

Updates use a locked full parameter snapshot, validate dependencies, update with optimistic version and publish cache only after commit.

## 10.2 Required explicit policy

Every consumer must be classified:

### Cached/fail-safe reads allowed

For optional runtime behavior where temporary DB unavailability may safely use last-known/static configuration.

### Authoritative reads required

For transitions/mutations where stale state could violate a safety gate.

Dream mutation/apply paths must retain the documented double-gate invariant: static configuration AND runtime DB authority as applicable.

## 10.3 Negative tests

- DB read failure with last-known-good -> defined cached behavior;
- DB read failure with no LKG -> defined static fallback;
- authoritative DB read failure -> explicit unavailable, never fallback;
- stale cached `true` must not authorize a mutation path that is specified to require authoritative state;
- dependency transitions remain atomic under concurrent admin updates;
- optimistic version conflict does not poison cache;
- failed transaction does not publish new cache value.

Create `docs/services/runtime-safety-flags.md` listing each `AppParameterKey`, its static fallback and which consumers require authoritative vs cached reads.

---

# 11. Adaptive graph mutation contract

## 11.1 Shared primitive

`GraphMutationLocks` is the canonical shared locking/lifecycle primitive used by:

- `AdaptiveChunkGraphRepository`;
- `SemanticAssociationSeedRepository`;
- `dream/SemanticGraphPriorWriter`.

Do not reintroduce duplicate advisory/lifecycle lock SQL in those writers.

## 11.2 Required invariants

- canonical node/pair ordering before locks;
- lifecycle rows locked before graph mutation;
- only eligible current generations mutate graph according to the specific online/semantic policy;
- bilateral edge mutation is atomic;
- reversed-pair concurrent writes do not deadlock;
- transaction/JDBC timeout bounds lock wait;
- half-pair exception rolls back both directions;
- Dream writer additionally enforces current fencing authority in guarded mutation DML.

## 11.3 Tests

Retain and explicitly map existing tests for:

- reversed pair concurrency;
- forced lifecycle/lock timeout;
- stale fencing token rollback;
- half-pair rollback.

Add missing tests only where a rule has no concrete coverage.

Create `docs/services/adaptive-graph-mutation.md`.

---

# 12. Dream cycle contract

## 12.1 Current orchestration

`AdaptiveGraphDreamCoordinator.runOnce()` currently:

1. checks `DreamRuntimeSwitches.enabled()`;
2. prevents local overlap via `AtomicBoolean`;
3. resolves policy;
4. acquires DB lease keyed by graph version/policy fingerprint;
5. creates bounded `DreamBudget`;
6. starts heartbeat and authority-loss callback;
7. initializes checkpoints and run row;
8. reserves capacity for bounded rescan;
9. processes fast lane;
10. processes rescan lane;
11. records partial/success/failure/lost-authority outcome;
12. clears per-run discovery state;
13. stops heartbeat;
14. attempts lease release;
15. clears local-run flag.

## 12.2 Fixed decisions that implementation must not reopen

- sequential owner/concurrency 1;
- fast lane is not completeness authority;
- bounded rescan supplies eventual coverage;
- PostgreSQL-time lease + fencing is distributed authority;
- static config + runtime DB gate is double safety gate;
- `ACTIVE` candidate means current semantic state;
- DREAM-4B per-source ACTIVE admission cap is not bilateral degree admission;
- ANN/query/transaction calls must be bounded by actual resource timeouts.

## 12.3 Required test matrix

- runtime disabled -> no lease attempt;
- local overlap -> skipped;
- lease held by other owner -> standby;
- heartbeat loses authority mid-run -> no further authoritative checkpoint/apply;
- fast lane empty + rescan continues;
- fast lane budget exhausted but reserved rescan still receives its capacity according to current contract;
- rescan cursor resumes after interruption;
- malformed cursor -> explicit failure/recovery policy;
- candidate drops below activation/retention -> stored state reflects current semantics;
- stale fencing token cannot apply prior;
- query timeout / transaction timeout actually interrupts underlying DB/ANN boundary;
- final run status cannot claim success after authority loss.

Create `docs/services/dream-cycle.md` and, if useful, separate `dream-candidate-lifecycle.md` only if the contract is independently meaningful; avoid duplicate architecture prose.

---

# 13. Scheduled-job classification

Create a table in `docs/services/scheduled-jobs.md` for every `@Scheduled` component.

Minimum known entries:

- `AdaptiveGraphDreamScheduler` — singleton via lease/fencing;
- `RetentionScheduler` — distributed claimed work;
- `GenerationReconciliationScheduler` — partitioned via row locks / `SKIP LOCKED` after PRA-04;
- `ReembeddingLeaseHeartbeatScheduler` — owner-scoped lease renewal, duplicate-safe only for same owner instance;
- `AdaptiveGraphMaintenanceScheduler` — classify and prove duplicate/multi-pod semantics;
- `AuditPartitionMaintenanceScheduler` — classify idempotence/locking;
- `RetentionEconomicsSampler` — classify as telemetry/sampling and define duplicate impact.

For every job record:

- trigger;
- static enable flag;
- runtime flag if any;
- multi-pod ownership model;
- transaction boundary;
- timeout;
- retry/recovery;
- metrics/logs;
- negative tests.

No job may remain documented simply as "scheduled periodically" without a multi-replica classification.

---

# 14. Documentation reconciliation

## 14.1 Current-state docs

Correct tracker-state drift for #36-#41. Historical snapshots may retain the old state only with explicit audited SHA/date wording.

## 14.2 `docs/README.md`

Update `Last synchronized against:` only as the final documentation commit immediately before final verification. If code changes after it, update it again.

## 14.3 `docs/services/service-inventory.md`

At implementation completion, review each row independently.

Expected candidates for `VERIFIED`, subject to final branch CI and contract review:

- Knowledge ingestion / Document ingestion;
- Retrieval / Query orchestration;
- Retrieval / policy routing;
- Retrieval / execution degradation;
- Retrieval / fusion-rerank-context selection.

New contracts remain `DRAFT` until their code/test work is complete on this branch.

## 14.4 No unverified tail commit

The commit that changes statuses to `VERIFIED` must itself go through final required checks. Therefore perform status promotion before the final CI run, not after it.

---

# 15. Implementation sequence

Execute in this order unless a newly reproduced P1 defect changes priority:

1. **CI branch coverage** — patch `.github/workflows/ci.yml` for `quality/**`, `docs/**`.
2. **Documentation truthfulness** — remove stale #36-#41 current-state claims.
3. **Reconciliation hardening** — `SKIP LOCKED`, fresh/stale PURGING semantics, concurrency tests.
4. **Publication contract/tests** — mostly documentation/test hardening unless a defect appears.
5. **Retention contract/tests**.
6. **Re-embedding contract/tests**.
7. **Graph mutation contract/test mapping**.
8. **Dream cycle/candidate contract/test mapping**.
9. **Runtime flag safety contract/tests**.
10. **Scheduled-job classification** and fill any uncovered multi-pod gaps.
11. **Approved baseline procedure/schema tests**; commit real `approved.json` only after an actual reviewed qualifying run.
12. **Service inventory reconciliation**.
13. **Final docs synchronization SHA**.
14. **Final exact-head CI / quality / storage / image verification**.
15. **Configure/verify branch protection required checks** using the confirmed check contexts.
16. **Run integrated release qualification** for the intended candidate/tag when approved baseline and external dependencies are available.

---

# 16. Commit discipline

Prefer small, reviewable commits in the same branch, for example:

```text
ci: cover quality and docs branches
fix: partition reconciliation work across pods
test: cover reconciliation crash and publication races
docs: define publication lifecycle contract
docs: define retention and reembedding contracts
test: map graph and dream failure invariants
docs: define runtime flag and scheduled-job contracts
docs: reconcile service inventory and audit state
```

Do not create parallel competing branches for these items.

---

# 17. Final Definition of Done

## Governance

- [ ] `main` protected by PR + required checks.
- [ ] pending/failed/cancelled required check blocks merge.
- [ ] force push/deletion protected according to repository policy.
- [ ] exact check contexts documented.

## CI

- [ ] `quality/**` push triggers CI.
- [ ] `docs/**` push triggers CI.
- [ ] final PR head passes formatting + `clean verify`.
- [ ] final PR head passes retrieval quality gate.
- [ ] final PR head passes storage benchmark gate.
- [ ] final PR head passes production image build.

## Lifecycle / reconciliation

- [ ] reconciliation candidate partitioning uses DB authority and `SKIP LOCKED` or an explicitly justified equivalent.
- [ ] no current published generation can be physically repaired/deleted.
- [ ] fresh PURGING work is not repeatedly reclaimed.
- [ ] stale PURGING work is recoverable.
- [ ] residual rows prevent false completion.
- [ ] multi-pod integration test proves no avoidable same-row blocking at discovery.

## Publication

- [ ] atomic publication contract documented.
- [ ] concurrent/stale/superseded publication cases tested.
- [ ] ambiguous commit outcome tested.
- [ ] post-commit semantic-linking failure cannot corrupt publication authority.

## Retention

- [ ] synchronous TTL fence documented/tested independently of scheduler.
- [ ] lease/claim/worker saturation/retry cases documented/tested.

## Re-embedding

- [ ] owner/lease/fencing contract documented.
- [ ] stale-owner takeover and heartbeat cases tested.
- [ ] startup recovery cannot abort healthy owner.

## Graph/Dream

- [ ] shared mutation lock primitive remains single source.
- [ ] reversed pair, timeout, half-pair rollback and stale fencing tests mapped/green.
- [ ] Dream fast/rescan/lease/budget/checkpoint semantics documented and tested.

## Runtime flags / jobs

- [ ] each runtime flag has defined cached vs authoritative read semantics.
- [ ] each scheduled job has a multi-pod classification.
- [ ] missing ownership/idempotence gaps are fixed or explicitly retained as documented bounded risk.

## Documentation

- [ ] current docs no longer claim closed #36-#41 are open.
- [ ] `docs/README.md` synchronization SHA matches final code/docs state.
- [ ] service inventory statuses match final evidence.
- [ ] no process marked `VERIFIED` without tests + green final SHA.

## Release qualification

- [ ] approved baseline schema/process verified.
- [ ] `approved.json` comes from a reviewed real pipeline run before formal release qualification.
- [ ] release manifest cannot report `qualified=true` with any failed constituent job.

## Exit

- [ ] no known P0/P1 correctness defect remains in this stabilization scope.
- [ ] remaining P2/P3 debt is explicitly documented with owner/process boundary and does not contradict production-readiness claims.
