# Post-retrieval project audit remediation specification

Status: **TARGET**  
Audit date: **2026-10-08**  
Source branch: `quality/post-retrieval-project-audit`  
Audited baseline: `main@f5aa953f0ca6bdf020e77830b14eda9e490f9955`

## 1. Purpose

This specification defines the stabilization and remediation work required after the retrieval-flow hardening line. The objective is to close confirmed governance, lifecycle, documentation and release-qualification gaps without introducing new product features.

The work is intentionally limited to correctness, availability, multi-replica safety, operability, release governance, test coverage and documentation accuracy.

## 2. Non-goals

The following are explicitly out of scope:

- new retrieval strategies;
- new graph-learning algorithms;
- ANN parallelism expansion;
- new Dream scoring models;
- ranking-policy expansion;
- UI/product functionality;
- replacing PostgreSQL as the distributed authority;
- changing the already fixed Dream ownership/fencing model without a demonstrated defect.

## 3. Audit findings and priorities

| ID | Priority | Area | Finding |
|---|---|---|---|
| PRA-01 | P1 | Governance | `main` is not protected and has no required status checks. |
| PRA-02 | P1 | Release process | PR #73 was merged before its exact-head CI completed. |
| PRA-03 | P1 | Release qualification | The release workflow expects an approved immutable RAG baseline that is absent from the audited repository state. |
| PRA-04 | P2 | Multi-pod lifecycle | Generation reconciliation can select the same cleanup batch on multiple pods and contend on the same rows. |
| PRA-05 | P2 | Documentation | Current docs claim issues #36-#41 are open although they are closed/completed. |
| PRA-06 | P2 | Documentation | `docs/README.md` synchronization SHA is stale. |
| PRA-07 | P2 | Documentation process | Service contracts remain `DRAFT` after exact-head green verification. |
| PRA-08 | P2 | CI | Push CI does not include `quality/**` and `docs/**` branches. |
| PRA-09 | P2 | Process coverage | Lifecycle, re-embedding, graph/Dream jobs, repair/reconciliation and runtime flags still lack the same service-contract/negative-case coverage now present for ingestion/retrieval. |

## 4. Global invariants

The remediation MUST preserve all of the following:

1. `main` must not accept a change before required checks succeed.
2. Release qualification must be reproducible for an exact immutable SHA/tag.
3. Multi-pod background workers must not duplicate expensive work unnecessarily or weaken existing lifecycle fences.
4. Publication, ACL, TTL and generation authority remain fail-closed.
5. Optional quality enhancers may degrade, but authority and visibility checks must never degrade open.
6. PostgreSQL-time lease/fencing remains the source of distributed authority where ownership is required.
7. Documentation must describe current executable behavior and tracker state, not historical assumptions.
8. A process contract may be marked `VERIFIED` only when implementation, positive/negative tests and exact-head CI evidence exist.
9. New fixes discovered while executing this specification must be completed in this branch unless they are unrelated feature work.

---

# 5. PRA-01 / PRA-02 — default-branch governance and merge gate

## 5.1 Problem

The default branch currently has no enforced required checks. This allowed PR #73 to merge before `mvn clean verify` completed. The later-successful CI result does not remove the governance defect.

## 5.2 Required repository configuration

Protect `main` using branch protection or an equivalent repository ruleset.

Minimum required controls:

- changes to `main` through pull request;
- block merge while required checks are pending;
- block merge when required checks fail;
- disallow force-push to `main`;
- disallow branch deletion;
- require branch to be up to date before merge when practical;
- do not allow administrator bypass for normal release changes unless an emergency procedure is explicitly documented.

Required normal checks SHOULD include the exact check names produced by the repository workflows for:

- CI / verify;
- Retrieval Quality Gate;
- Retrieval Storage Final Benchmark;
- Production Image Build.

The exact configured GitHub check contexts MUST be copied from a current successful PR/check suite rather than guessed.

## 5.3 Release-tag governance

The live `RAG v1.1 Release Qualification` workflow is a release/tag promotion gate, not a normal PR gate. Formal version tags MUST only be created from a commit that already satisfies normal `main` gates and has an approved baseline.

## 5.4 Emergency bypass

If owner-level emergency bypass remains possible, document:

- who may invoke it;
- acceptable reasons;
- required follow-up PR;
- required retrospective verification;
- prohibition on silently treating bypassed commits as quality-stable baselines.

## 5.5 Positive tests / verification

- Open a test PR with all required checks green; merge control allows merge only after completion.
- Confirm direct non-PR update to `main` is rejected for normal contributor path.
- Confirm force-push/deletion restrictions.

## 5.6 Negative tests / verification

- Pending required check -> merge blocked.
- Failed required check -> merge blocked.
- Cancelled required check -> merge blocked.
- Stale/out-of-date PR behaves according to configured update policy.

## 5.7 Acceptance criteria

- A PR cannot reproduce the #73 sequence `merge -> verify later`.
- Repository metadata/rules prove the enforcement.
- `docs/operations/production-release-gates.md` describes the final configured rules and exact check contexts.

---

# 6. PRA-03 — approved immutable RAG baseline and release qualification

## 6.1 Problem

`.github/workflows/rag-v1.1-release-qualification.yml` consumes:

`benchmarks/rag-benchmark-v1/baselines/approved.json`

with baseline establishment disabled. The approved baseline is therefore an external precondition of reproducible release qualification.

## 6.2 Required baseline process

Create a controlled baseline-establishment procedure:

1. Select an exact candidate SHA.
2. Run the benchmark against the canonical controlled corpus using the production pipeline, not mocked retrieval.
3. Retain raw benchmark result artifacts.
4. Human-review the result for corpus validity, answerability labels, retrieval metrics and abstention behavior.
5. Materialize `approved.json` from that reviewed run.
6. Commit it with provenance fields sufficient to identify the source SHA/run/corpus/schema.
7. Treat changes to approved baseline as review-required release engineering changes, never as automatic CI output.

## 6.3 Baseline schema requirements

At minimum the baseline artifact SHOULD contain or reference:

- schema version;
- source git SHA;
- corpus version/fingerprint;
- benchmark runner version/contract;
- Recall/MRR/nDCG or the currently authoritative retrieval metrics;
- grounding/citation/abstention thresholds used by the release gate;
- establishment timestamp;
- reviewer/provenance note suitable for repository history.

Do not add fields that the current parser cannot tolerate without first updating its schema/parser tests.

## 6.4 Positive cases

- Candidate equal/better than approved thresholds -> qualification quality job succeeds.
- Exact baseline file + supported schema -> deterministic comparison.

## 6.5 Negative cases

- Missing baseline -> release qualification must fail explicitly.
- Malformed baseline -> fail explicitly, no silent default.
- Corpus fingerprint mismatch -> fail or require deliberate migration; never silently compare incompatible corpora.
- Baseline generated from mocked retrieval -> invalid for approval.
- Baseline produced by a failed/incomplete benchmark -> invalid for approval.

## 6.6 Acceptance criteria

- `approved.json` exists only after a reviewed real run.
- Release qualification on a candidate SHA produces retained quality/performance/grounding artifacts and final manifest.
- `qualified=true` is impossible when any constituent job fails.

---

# 7. PRA-04 — multi-pod generation reconciliation hardening

## 7.1 Current behavior

`GenerationReconciliationScheduler` runs independently on every application replica. `GenerationReconciliationService.reconcileBatch()` discovers candidates with an ordered `SELECT ... LIMIT ?`, then obtains row locks later while processing individual candidates.

This is correctness-safe only to the extent that later lifecycle/generation locks serialize mutations, but it permits duplicate candidate selection and avoidable multi-pod contention.

## 7.2 Target behavior

Multiple replicas MAY run reconciliation, but a candidate generation SHOULD be actively worked by at most one replica at a time.

Preferred design: DB-backed bounded batch claiming using one of the following, selected after implementation-level review:

### Option A — `FOR UPDATE SKIP LOCKED`

Use a short claim transaction to select eligible generation rows with deterministic ordering and `FOR UPDATE SKIP LOCKED`, then process only claimed rows.

This option is preferred if the complete reconciliation of a generation can safely be represented by a short-lived database claim without holding a long transaction across repair work.

### Option B — persisted claim/lease

Add explicit claim columns/table if repair work must outlive a row-lock transaction:

- owner/worker identity;
- lease-until using PostgreSQL time;
- monotonic fencing token if stale owners can mutate after takeover;
- claim/renew/release semantics.

Do NOT hold database row locks across long external/vector cleanup operations solely to obtain singleton behavior.

## 7.3 Required properties

- bounded work per scheduler tick;
- no unbounded transaction around the full cleanup operation;
- deterministic claim order;
- crash recovery;
- stale claim recovery if persisted leases are used;
- publication lifecycle is rechecked before destructive cleanup;
- no cleanup of the currently published generation;
- retry remains idempotent;
- audit events must not claim success before cleanup is actually final.

## 7.4 Positive tests

- one replica claims and cleans an eligible terminal generation;
- two workers with disjoint eligible rows process in parallel;
- a completed cleanup becomes `CLEANED` exactly once at lifecycle level;
- bounded repair requiring a later run remains eligible for retry.

## 7.5 Negative/concurrency tests

- two workers race for the same generation -> only one owns active processing;
- published generation becomes current after discovery but before destructive work -> cleanup aborts/fails closed;
- worker crashes after claim -> another worker eventually recovers the work;
- stale owner after takeover cannot finalize a newer owner's work if fencing is introduced;
- DB exception during claim -> no partial ownership state;
- DB exception during finalization -> retry remains safe;
- duplicate scheduler ticks do not multiply expensive repair work for the same generation.

## 7.6 Observability

Add/confirm metrics for:

- candidates discovered;
- candidates claimed;
- claim conflicts/skips;
- cleaned;
- deferred;
- failed;
- run duration;
- batches;
- stale-claim recovery if leases are used.

## 7.7 Acceptance criteria

- multi-replica deterministic test demonstrates no duplicate active repair for one generation;
- no regression to current publication/lifecycle fencing;
- no long-lived transaction is introduced around expensive repair calls;
- process contract is documented under `docs/services/`.

---

# 8. PRA-05 / PRA-06 — documentation truthfulness cleanup

## 8.1 Required updates

Update all current documentation that still states issues #36-#41 are open. Their tracker state is now closed/completed.

At minimum inspect and correct:

- `docs/README.md`;
- `docs/audit/README.md`;
- `docs/audit/post-v1.1-readiness-2026-10-08.md`;
- any current readiness/remediation document that uses the old open-state claim.

Historical documents MAY retain the old state only when clearly classified as historical snapshots tied to an older audited SHA.

## 8.2 Synchronization marker

Update `docs/README.md` synchronization SHA only after the branch's final code/docs state is known. Do not set it early and then add later changes without updating it again.

## 8.3 Positive cases

- Current docs reflect current GitHub tracker state.
- Historical snapshot explicitly states the historical audit SHA/date.

## 8.4 Negative cases

- No current document says an issue is open when GitHub state is closed.
- No current document presents a superseded remediation plan as current behavior.
- No duplicated current architecture documents with conflicting rules.

## 8.5 Acceptance criteria

- Documentation search for `#36`, `#37`, `#38`, `#39`, `#40`, `#41` is manually reviewed.
- Current docs agree with code, migrations, config and actual issue state.

---

# 9. PRA-07 — service inventory verification lifecycle

## 9.1 Problem

The inventory defines `VERIFIED` as implementation + concrete tests + green verification, but the status is not consistently promoted after exact-head success.

## 9.2 Required change

Review each existing contract individually:

- `knowledge-ingestion.md`;
- `retrieval-flow.md`;
- `retrieval-routing.md`;
- `retrieval-execution.md`;
- `retrieval-selection.md`.

Promote to `VERIFIED` only if:

1. the document matches current `main` behavior;
2. referenced tests exist and cover the declared positive/negative semantics;
3. the implementation containing those tests passed CI on an exact SHA;
4. no material unresolved correctness gap invalidates the contract.

If any condition is false, keep `DRAFT` and record the explicit missing item in the contract.

## 9.3 Process hardening

Add a documentation/release checklist item to every future hardening PR:

`[ ] service-inventory status reconciled after final green SHA`

The final status update must itself be included in a SHA that passes required checks; do not create an unverified documentation-only tail commit and call the prior SHA final.

---

# 10. PRA-08 — CI branch trigger consistency

## 10.1 Required change

Update `.github/workflows/ci.yml` push branches to include the repository's actual stabilization/documentation naming conventions, at minimum:

```yaml
- "quality/**"
- "docs/**"
```

Keep `pull_request:` coverage unchanged.

## 10.2 Positive cases

- push to `quality/...` triggers CI;
- push to `docs/...` triggers CI;
- PR still triggers CI regardless of branch prefix.

## 10.3 Negative cases

- duplicated simultaneous workflows must not be introduced accidentally by conflicting trigger definitions;
- branch-prefix addition must not weaken required PR checks.

---

# 11. PRA-09 — remaining process-contract hardening

The next audit/remediation layer must apply the same `Process -> Business Rules -> Positive -> Negative -> Tests` standard already used for ingestion and retrieval.

The order is mandatory unless a P1 defect discovered during execution changes priority.

## 11.1 Publication / activation lifecycle

Document and test:

- generation publication transaction boundary;
- previous generation retirement transition;
- publication ambiguity/retry;
- publication conflict/concurrency;
- ACL identity consistency;
- failure before publication;
- failure after staged storage but before publication;
- revalidation of currently published generation;
- idempotent/recoverable publication semantics.

Negative cases MUST include:

- stale generation attempting publication;
- two concurrent publication attempts for one document;
- lifecycle row disappears/changes;
- DB timeout/deadlock/transaction rollback;
- partial vector/projection staging;
- retry after ambiguous commit outcome.

## 11.2 Chunk lifecycle / retention

Document and test:

- TTL synchronous visibility fence;
- scheduler vs synchronous eligibility responsibility;
- retiring/retired/cleaned transitions;
- retention lease and worker-pool behavior;
- cleanup retry limits;
- tombstone creation/verification;
- expired-but-still-ACTIVE retrieval rejection;
- scheduler disabled/failure behavior.

## 11.3 Re-embedding

Document and test:

- owner lease;
- heartbeat;
- fencing token;
- stale owner rejection;
- takeover after lease expiry;
- staging/cutover transaction boundary;
- rollback/failure/recovery;
- multi-replica startup while healthy owner exists;
- `auto-migrate=false` recovery behavior.

## 11.4 Repair / reconciliation

In addition to PRA-04, cover:

- orphan projection/vector cleanup;
- bounded batch exhaustion;
- residual rows remaining after repair;
- audit correctness;
- retry after partial physical cleanup;
- missing embedding profile;
- tombstone mismatch;
- cleanup DB timeout.

## 11.5 Adaptive graph mutation

Document and test:

- canonical pair ordering;
- graph mutation locking;
- lifecycle eligibility;
- online vs semantic mutation policy differences;
- rollback on half-pair failure;
- reversed-pair concurrency;
- timeout while waiting for lock;
- stale lifecycle state;
- quota enforcement.

## 11.6 Dream ownership / candidate lifecycle

Preserve fixed decisions:

- fast lane is an accelerator only;
- bounded rescan provides eventual coverage;
- one serial Dream owner;
- PostgreSQL-time lease + fencing authority;
- config + runtime DB double gate;
- candidate `ACTIVE` means current semantic state;
- `maxNewEdgesPerChunk` is per-source per-run ACTIVE admission in DREAM-4B;
- ANN concurrency remains 1 in this stabilization scope.

Document/test:

- acquire/renew/release;
- lease loss during run;
- stale fencing token at mutation;
- runtime flag changes during execution;
- timeout at JDBC/ANN boundary;
- bounded rescan cursor corruption/recovery;
- candidate activation/demotion/forgetting;
- interrupted run idempotency;
- source disappearance or lifecycle invalidation.

## 11.7 Scheduled/background jobs

Build a single inventory of every `@Scheduled` process and classify each as:

- safe on every pod;
- DB-claimed work distribution;
- singleton lease/fenced owner;
- telemetry-only duplicate-safe.

Every mutating scheduled job must have an explicit multi-pod safety statement and test or rationale.

Candidate jobs include at least:

- Dream scheduler;
- graph maintenance;
- generation reconciliation;
- retention scheduler;
- retention economics sampler;
- audit partition maintenance;
- re-embedding heartbeat/recovery-related schedulers.

## 11.8 Runtime feature flags / safety gates

Document/test:

- static config fallback;
- runtime DB override;
- fail-safe behavior when app-parameter storage is unavailable;
- double-gate semantics for dangerous mutation/apply flows;
- cache TTL/staleness behavior;
- enable/disable transitions while jobs are running;
- observability of effective runtime state.

---

# 12. Testing strategy

## 12.1 Unit tests

Use for deterministic business rules, validation, state transitions and fail-open/fail-closed policy.

## 12.2 PostgreSQL integration tests

Mandatory for behavior involving:

- `clock_timestamp()`;
- row/advisory locks;
- `FOR UPDATE` / `SKIP LOCKED`;
- transaction timeout;
- lease/fencing;
- migrations/indexes;
- actual TTL eligibility predicate;
- multi-session concurrency.

Mocks are not sufficient evidence for these invariants.

## 12.3 Failure injection

Required where applicable:

- DB exception;
- transaction timeout;
- lock wait;
- executor rejection;
- worker interruption;
- stale fencing token;
- lease expiry;
- partial cleanup;
- ambiguous completion;
- dependency unavailable;
- runtime flag transition.

## 12.4 Concurrency tests

Concurrency tests must be deterministic enough to prove ordering/ownership using latches, barriers, DB locks or explicit test fixtures rather than timing-only sleeps where avoidable.

---

# 13. Observability requirements

Each remediated process must expose enough telemetry to distinguish:

- success;
- legitimate no-op/empty work;
- degraded/retryable failure;
- critical/unavailable failure;
- ownership/claim loss;
- timeout;
- stale state rejection.

Do not allow metrics/logging exceptions to change business behavior unless observability is itself the contractual operation.

Logs MUST NOT expose API keys, DB passwords, sensitive document payloads or unbounded query/context content.

---

# 14. Transaction and timeout requirements

For every changed DB process, document explicitly:

- transaction start/end;
- what rows are locked;
- lock ordering;
- statement/query timeout;
- transaction timeout;
- what external work occurs outside the transaction;
- retry/idempotency behavior after rollback;
- stale-owner/fencing behavior where applicable.

Long external operations MUST NOT be hidden inside a broad database transaction merely for convenience.

---

# 15. Documentation deliverables

Expected new/current service contracts after implementation:

- `docs/services/publication-lifecycle.md`;
- `docs/services/chunk-lifecycle-retention.md`;
- `docs/services/reembedding.md`;
- `docs/services/repair-reconciliation.md`;
- `docs/services/adaptive-graph-mutation.md`;
- `docs/services/dream-ownership-candidate-lifecycle.md`;
- `docs/services/scheduled-jobs.md`;
- `docs/services/runtime-feature-flags.md`.

Names MAY be adjusted to match actual implementation boundaries, but do not create overlapping duplicate contracts.

Each contract must include:

```text
Purpose
Inputs / entry points
Process
Business Rules
Positive Cases
Negative Cases
Invariants
Transaction boundary
Timeout / cancellation
Concurrency / fencing
Retries / idempotency
Observability
Operational recovery
Tests
```

---

# 16. Implementation order

Execute in this order:

1. **Governance evidence/spec** — record exact required check names and configure/prove branch protection/ruleset.
2. **CI trigger consistency** — add `quality/**` and `docs/**` push coverage.
3. **Documentation truth cleanup** — issue states, sync SHA, stale current claims.
4. **Service inventory reconciliation** — promote only genuinely verified contracts.
5. **Generation reconciliation multi-pod hardening** — implement and test claim semantics.
6. **Publication lifecycle contract/tests**.
7. **Chunk lifecycle/retention contract/tests**.
8. **Re-embedding contract/tests**.
9. **Repair/reconciliation full contract/tests**.
10. **Adaptive graph mutation contract/tests**.
11. **Dream ownership/candidate lifecycle contract/tests**.
12. **Scheduled-jobs multi-pod classification/tests**.
13. **Runtime feature flag contract/tests**.
14. **Approved baseline establishment** using a reviewed real benchmark run.
15. **Final exact-SHA CI + quality + storage + image verification**.
16. **Integrated v1.1 live qualification** and retained artifacts for release candidate.
17. **Final docs sync SHA and inventory statuses** on the same verified final change set.

If a P0/P1 correctness defect is found during steps 5-13, fix it before continuing to qualification work.

---

# 17. Definition of Done

The branch is complete only when all applicable items are true:

```text
[ ] main merge governance is enforced and evidenced
[ ] pending/failed required checks block merge
[ ] CI triggers cover quality/** and docs/**
[ ] current docs no longer claim #36-#41 are open
[ ] docs synchronization marker matches final verified state
[ ] verified service contracts are accurately marked VERIFIED
[ ] reconciliation is multi-pod work-claim safe
[ ] publication lifecycle has positive/negative/concurrency tests
[ ] retention/lifecycle has positive/negative/concurrency tests
[ ] re-embedding has lease/fencing/takeover tests
[ ] repair/reconciliation has failure and retry tests
[ ] graph mutation contract covers locking/rollback/timeouts
[ ] Dream contract covers ownership/fencing/rescan/candidate lifecycle
[ ] every mutating scheduled job has explicit multi-pod safety semantics
[ ] runtime feature flags have failure/staleness/double-gate tests
[ ] approved immutable benchmark baseline exists with provenance
[ ] final branch head passes formatting + clean verify
[ ] final branch head passes Retrieval Quality Gate
[ ] final branch head passes Retrieval Storage Final Benchmark
[ ] final branch head passes Production Image Build
[ ] release candidate passes integrated live qualification before formal release claim
[ ] retained artifacts identify exact SHA/ref and qualification result
[ ] no known P0/P1 correctness defect remains in scope
```

# 18. Exit rule

This branch is stabilization-only. Do not extend it with new adaptive-RAG features after the above DoD is satisfied. New capabilities must start from the resulting verified baseline in a separate feature branch.
