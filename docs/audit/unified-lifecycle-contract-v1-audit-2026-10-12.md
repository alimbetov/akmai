# Unified lifecycle contract v1 — implementation audit

Audit date: 2026-10-12

Audited ref: `feature/unified-lifecycle-contract-v1@f59fe0ebee6f01e13245b2b5ba09bcc9b3f3d72f`

Target contract: `docs/architecture/unified-lifecycle-contract-v1.md`

Classification: **CURRENT AUDIT SNAPSHOT / IMPLEMENTATION GAP LEDGER**

## Scope

This audit compares the executable lifecycle behavior inherited from `main@c8ca0d9263e2b481ef6c669c8174276160e7a769` with the TARGET unified lifecycle contract. The branch itself is documentation-only at the audited SHA, so implementation findings below describe inherited runtime behavior rather than code already changed by this branch.

The audit covers:

- document TTL and retention claims;
- generation allocation/publication/supersession;
- retrieval/final-context eligibility;
- projection/vector/reference/identifier/query-memory eligibility;
- adaptive graph read/mutation eligibility;
- Dream source/candidate/apply behavior;
- failure/race evidence relevant to INV-01..INV-12.

This document distinguishes implementation defects from evidence gaps. It does not claim release qualification.

## Executive assessment

The project already has strong lifecycle primitives: PostgreSQL-time TTL checks, generation-scoped publication, retention claims with fencing, final-context revalidation, graph mutation row/advisory locking, Dream lease fencing, and generation-scoped cleanup. The main remaining problem is not absence of lifecycle controls; it is that authority semantics are split across several independently maintained SQL boundaries.

The TARGET is therefore directionally correct, but Stage 1 must be tightened before Stage 2 implementation. Two P0 issues should be addressed first:

1. replacement ingestion currently makes the previously published generation unavailable at allocation time instead of only at successful replacement publication;
2. the existing shared lifecycle SQL predicate is weaker than the TARGET canonical predicate because it does not prove `knowledge_document_generation.generation_status='PUBLISHED'`.

## Current authority inventory

| Consumer / transition | Current authority check | Assessment |
|---|---|---|
| Retrieval final eligibility | `PublishedLifecycleSql`: `READY + ACTIVE + TTL`, plus exact `published_generation` identity in callers | Strong base, but does not prove generation-table `PUBLISHED` state |
| Vector / lexical / projection / identifier / reference lanes | several consumers reuse `PublishedLifecycleSql` | Good partial canonicalization |
| Query Memory | independent lifecycle SQL with `READY + ACTIVE + published_generation + TTL` | Semantic duplicate; no generation-table `PUBLISHED` proof |
| Graph ANN / semantic neighbor search | independent lifecycle SQL with `READY + ACTIVE + published_generation + TTL` | Semantic duplicate; no generation-table `PUBLISHED` proof |
| Graph mutation | lifecycle row `FOR SHARE`, separate exact generation `PUBLISHED` row `FOR SHARE`, then advisory locks | Correctness is comparatively strong, but semantics are duplicated and must remain lock-safe during canonicalization |
| Dream source enumeration | independent lifecycle SQL with `READY + ACTIVE + published_generation + TTL` | Semantic duplicate; no generation-table `PUBLISHED` proof |
| Dream candidate persistence | Dream lease/fencing authority only | Endpoint lifecycle is not re-proved at candidate write time |
| Dream graph apply | `GraphMutationLocks.lockEligiblePublishedNodes(...)` | Strong late apply fence |
| Generation publication | document lifecycle row `FOR UPDATE`; old generation -> `RETIRING`; new generation -> `PUBLISHED`; lifecycle points to new generation in one transaction | Strong atomic publication boundary |
| Replacement generation allocation | lifecycle row becomes `INGESTING` immediately | Conflicts with TARGET availability semantics because existing published generation becomes ineligible during staging |
| Retention claim | PostgreSQL time, claim generation/id, worker id, lease, `SKIP LOCKED` | Strong base |
| Re-ingestion vs retention claim | allocation clears stale delete claim under lifecycle-row lock | Existing race protection is useful and should be retained |

## Defect ledger

### LFC-01 — P0 — replacement ingestion invalidates the old generation too early

`DocumentGenerationRepository.allocate(...)` updates `knowledge_document_lifecycle.lifecycle_status='INGESTING'` even when `published_generation` still points to an existing valid generation. All canonical retrieval/graph/Dream eligibility paths require `lifecycle_status='READY'`, so the currently published generation disappears from online use as soon as replacement ingestion starts.

This differs from the TARGET generation contract:

```text
G(n) remains online while G(n+1) is STAGING
successful publication atomically:
  G(n)   -> RETIRING / logically superseded
  G(n+1) -> PUBLISHED / online
```

Current behavior instead creates:

```text
G(n) PUBLISHED + lifecycle READY
  -> allocate G(n+1)
  -> lifecycle INGESTING
  -> G(n) becomes online-ineligible
  -> ingest/build G(n+1)
  -> publication restores lifecycle READY
```

This is an availability/correctness semantic gap, not merely documentation drift. The existing retrieval test that rejects a published generation while lifecycle is `INGESTING` confirms the current boundary, but that test should be revised once the unified contract is implemented.

Required direction:

- use `knowledge_document_generation` to represent `G(n+1)=STAGING`;
- preserve document online readiness while an older `published_generation` exists;
- set document lifecycle `INGESTING` only when there is no published generation to serve, or introduce an explicit non-destructive staging representation;
- retain claim invalidation/fencing performed during allocation.

Impacted invariants: INV-04, INV-05, INV-09.

### LFC-02 — P0 — shared eligibility predicate is weaker than the TARGET canonical predicate

`PublishedLifecycleSql.eligible(alias)` currently owns only:

```text
lifecycle_status = READY
retention_status = ACTIVE
TTL not expired
```

Callers generally bind the lifecycle row to `published_generation`, but `PostgresPublishedLifecycleEligibility` does not join `knowledge_document_generation` and therefore does not prove that the pointed generation row is still `generation_status='PUBLISHED'`.

The TARGET canonical predicate explicitly requires both lifecycle authority and generation authority:

```text
lifecycle READY
retention ACTIVE
published_generation = generation
generation_status = PUBLISHED
TTL valid
```

Publication is transactional and the unique partial index on published generations reduces normal drift risk, but a unified lifecycle contract should fail closed under repair/recovery/corruption states rather than relying on implicit cross-table consistency.

Required direction:

- define one canonical lifecycle+generation identity boundary;
- keep ACL/access-level identity independent and mandatory;
- provide a lock-capable variant for mutation paths rather than forcing hot read paths into extra JDBC round trips;
- add a negative integration test where lifecycle points to a generation whose generation row is `RETIRING`, `FAILED` or absent.

Impacted invariants: INV-04, INV-05, INV-06, INV-07.

### LFC-03 — P1 — predicate drift remains across Query Memory, Graph and Dream

Several online paths still embed their own copies of:

```text
READY
ACTIVE
published_generation identity
expires_at > clock_timestamp()
```

Observed examples include:

- `QueryMemorySourceEligibility`;
- `SemanticNeighborSearchRepository`;
- `DreamSourceRepository`;
- `GraphMutationLocks` lifecycle half.

The copies are currently similar, but they are not mechanically tied to the same contract. This is precisely the drift class the branch is intended to remove.

Required direction:

- promote the existing lifecycle helper into the canonical generation-eligibility primitive rather than creating a parallel service/store;
- migrate read-side consumers first;
- preserve graph mutation lock ordering and transaction semantics explicitly when sharing the predicate.

Impacted invariants: INV-01, INV-02, INV-03, INV-07, INV-08, INV-12.

### LFC-04 — P1 — Dream candidates can remain `ACTIVE` without endpoint lifecycle revalidation

`DreamCandidateRepository.observe(...)` gates writes with Dream lease/fencing authority, but does not revalidate the two endpoint generations. A candidate can therefore be discovered while endpoints are eligible, lose endpoint authority before candidate persistence, and still be stored as `ACTIVE` if the Dream lease remains valid.

`findActivePairsForSource(...)` also reads `ACTIVE` candidate rows without joining lifecycle/generation authority.

The graph apply path is safer because `SemanticGraphPriorWriter` uses `GraphMutationLocks.lockEligiblePublishedNodes(...)`, so stale Dream candidates cannot directly resurrect graph state. However the TARGET says Dream derived state itself must become non-usable immediately after authority loss; stale `ACTIVE` rows can still influence Dream retention/coordination decisions before a later apply fence rejects them.

Required direction:

- re-prove endpoint eligibility on candidate observation/activation or ensure `ACTIVE` reads are lifecycle-filtered;
- make lifecycle invalidation dominate Dream state;
- use asynchronous `STALE`/retirement cleanup only after logical ineligibility is already enforced.

Impacted invariants: INV-03, INV-08, INV-09.

### LFC-05 — P1 — Stage 1 state mapping is not yet complete

The TARGET semantic states are useful, but there is no executable/current mapping table for existing storage states such as:

- lifecycle: `INGESTING`, `READY`, `INGEST_FAILED`, `DELETE_PENDING`, `DELETING`, `DELETE_FAILED`, `DELETED`;
- retention: `ACTIVE`, `DELETE_PENDING`, `DELETING`, `DELETED`;
- generation: `STAGING`, `PUBLISHED`, `RETIRING`, `FAILED`, cleaned/retired metadata;
- Dream: `CANDIDATE`, `ACTIVE`, `STALE`, `REJECTED`;
- graph local temperature/decay states.

Without that mapping, `STAGING -> ACTIVE -> SUPERSEDED -> RETIRING -> RETIRED` remains descriptive rather than a proof contract.

Required direction: add a transition matrix listing allowed transition, owning transaction, lock/fence, online eligibility before/after, recovery behavior and required test.

Impacted invariants: all, especially INV-05, INV-09, INV-10, INV-11.

### LFC-06 — P1 — failure-injection evidence exists but is fragmented, not contract-complete

Useful existing evidence includes:

- real PostgreSQL TTL/final-context revalidation;
- retention/re-ingestion claim invalidation;
- graph mutation transaction locks;
- Dream lease fencing.

However the audited branch adds no executable tests and does not yet map current tests to INV-01..INV-12. Therefore Stage 4 acceptance cannot be claimed even where partial coverage already exists.

Required contract-level tests:

1. TTL expires between candidate production and final-context commit;
2. `G(n+1)` ingestion starts while `G(n)` remains online;
3. publication atomically flips online authority from `G(n)` to `G(n+1)`;
4. lifecycle points to non-`PUBLISHED` generation -> every consumer fails closed;
5. Dream candidate discovered, endpoint expires, candidate write/apply is rejected or immediately non-usable;
6. graph mutation endpoint invalidates while lock race is in progress;
7. retention lease lost during destructive cleanup;
8. `G(n+1)` publishes while `G(n)` purge is active;
9. pod-local clock skew has no effect on TTL/lease decisions;
10. retry/restart does not reactivate retired derived state.

### LFC-07 — P2 — documentation index is only partially wired

`docs/README.md` links the TARGET lifecycle contract, but `docs/architecture/README.md` does not yet list it in the architecture start page or storage/lifecycle section. This is a documentation discoverability issue, not a runtime defect.

## Positive findings to preserve

The implementation should reuse, not replace, the following working primitives:

- PostgreSQL `clock_timestamp()` for TTL/lease decisions;
- lifecycle row locking during publication/allocation;
- generation-scoped `knowledge_document_generation` state;
- partial uniqueness of `PUBLISHED` generation per document;
- retention claim generation/id/worker/lease fencing;
- `FOR UPDATE SKIP LOCKED` retention claiming;
- final-context revalidation after earlier retrieval phases;
- canonical graph node lock ordering;
- graph lifecycle row -> generation row -> advisory lock order;
- Dream fencing token/lease checks;
- generation-scoped physical cleanup and residual verification.

A new Redis coordinator, new lifecycle store, or synchronous purge path would add risk without solving the audited gaps.

## Stage readiness

| Stage | Status | Audit conclusion |
|---|---|---|
| Stage 1 — contract + invariant matrix | **PARTIAL / NOT EXITED** | TARGET exists, but transition/state mapping and exact consumer inventory are incomplete; replacement-ingestion semantics conflict with TARGET |
| Stage 2 — canonical eligibility | **PARTIAL BASE EXISTS** | `PublishedLifecycleSql` is a useful seed; Graph/Query Memory/Dream still duplicate predicates and generation-table proof is missing |
| Stage 3 — propagation | **PARTIAL** | publication and graph apply fencing are strong; staging availability and Dream candidate invalidation remain gaps |
| Stage 4 — failure-injection proof | **NOT COMPLETE** | useful tests exist, but no complete INV-01..INV-12 / race-matrix evidence on this branch |

## Recommended implementation order

1. **Fix LFC-01 first.** Preserve the old published generation while replacement generation is staging. This establishes the correct generation lifecycle before canonicalization spreads the semantics further.
2. **Close LFC-02.** Define one lifecycle+generation eligibility contract with read and lock-capable forms.
3. **Migrate LFC-03 consumers.** Retrieval/projection first, then Query Memory, semantic graph search, Dream sources; keep graph mutation lock order explicit.
4. **Close LFC-04.** Make Dream ACTIVE candidate usability endpoint-authority-aware at persistence/read boundaries.
5. **Complete Stage 1 matrices.** Map all storage states and transitions to semantic lifecycle states and invariant IDs.
6. **Build Stage 4 proof suite.** Reuse existing Testcontainers/PostgreSQL fixtures but make the race matrix explicit and repeatable.
7. Update `current-runtime-architecture.md`, adaptive graph/Dream docs and architecture indexes only after runtime semantics are implemented and tests pass.

## Exit decision

`feature/unified-lifecycle-contract-v1` is **well-scoped but not implementation-ready for blind refactoring**. The right next move is a small correctness-first patch sequence, starting with replacement-ingestion visibility and the canonical lifecycle+generation predicate. After those two P0 items are fixed, the remaining work is mostly consolidation, Dream invalidation hardening and proof coverage rather than a new lifecycle subsystem.
