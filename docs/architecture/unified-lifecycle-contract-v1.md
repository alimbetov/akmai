# Unified lifecycle contract v1

Status: `TARGET`

Branch: `feature/unified-lifecycle-contract-v1`

Baseline: `main@c8ca0d9263e2b481ef6c669c8174276160e7a769`

## Purpose

Close the remaining lifecycle correctness gap by making document retention, generation publication, parent/child visibility, adaptive-graph aging and Dream forgetting one coherent and testable contract.

The target is not to add another independent lifecycle subsystem. Existing storage, fencing, generation and cleanup primitives remain the implementation basis. The change must centralize authority and eligibility semantics, remove predicate drift, propagate invalidation consistently and prove race/failure behavior with integration tests.

## Authority hierarchy

Lifecycle authority is strictly ordered:

```text
Document lifecycle
  -> Published generation
    -> Parent/child eligibility
      -> Chunk/vector/identifier/reference eligibility
        -> Adaptive graph endpoint/edge eligibility
          -> Dream candidate eligibility
```

A derived object must never be online-eligible after the authority object from which it derives becomes ineligible.

## Canonical online-generation predicate

The logical contract is:

```text
generationOnline(documentId, generation) :=
    lifecycle_status = READY
    AND retention_status = ACTIVE
    AND published_generation = generation
    AND generation_status = PUBLISHED
    AND (expires_at IS NULL OR expires_at > database_now)
```

`database_now` is PostgreSQL time. Application-node clocks must not decide distributed lifecycle validity.

Physical cleanup may lag logical invalidation. Logical invalidation must never lag physical cleanup.

The executable implementation uses `PublishedLifecycleSql` as the canonical read-side SQL primitive. `eligible(lifecycleAlias)` proves the lifecycle predicate plus existence of the referenced `PUBLISHED` generation; `publishedGenerationEligible(lifecycleAlias, generationAlias)` provides the mechanically equivalent join-based form for hot paths that already join `knowledge_document_generation`.

## Lifecycle states

The semantic state model is:

```text
STAGING -> ACTIVE -> SUPERSEDED -> RETIRING -> RETIRED
               \---------------------> RETIRING
```

Existing table-specific enums remain where compatible. The semantic state is derived from authoritative storage state; it is not a second persisted state machine.

### Physical-to-semantic state mapping

| Authoritative physical state | Semantic state | Online eligible? | Notes |
|---|---|---:|---|
| no published generation; current generation `STAGING`; lifecycle `INGESTING` | `STAGING` | no | first ingestion has nothing older to serve |
| lifecycle `READY/ACTIVE`, unexpired; `published_generation=G`; generation `G=PUBLISHED` | `ACTIVE` | yes | only canonical online state |
| `G(n)=PUBLISHED` while `G(n+1)=STAGING`; lifecycle remains `READY/ACTIVE` and points to `G(n)` | `ACTIVE` for `G(n)`, `STAGING` for `G(n+1)` | only `G(n)` | replacement ingestion must not create an availability hole |
| replacement publication transaction changes old generation to `RETIRING` and new generation to `PUBLISHED` | old `SUPERSEDED/RETIRING`, new `ACTIVE` | only new generation | authority flip is atomic |
| lifecycle TTL has expired while physical rows still exist | `RETIRING` logically | no | logical invalidation precedes asynchronous purge |
| lifecycle/retention `DELETE_PENDING` or `DELETING` | `RETIRING` | no | claim/lease/fencing owns destructive work |
| generation `RETIRING` or lifecycle points elsewhere | `SUPERSEDED/RETIRING` | no | stale payload may still exist physically |
| generation `RETIRED/CLEANED` or lifecycle `DELETED` | `RETIRED` | no | terminal for online use |
| generation `FAILED` with an older valid `published_generation` | failed staging attempt; older generation remains `ACTIVE` | older generation only | failed replacement must not create downtime |
| Dream candidate `ACTIVE` whose endpoint loses generation authority | derived `RETIRED/STALE` logically | no | physical row may remain `ACTIVE` until retirement, but read/write boundaries filter it immediately |

### Transition matrix

| Transition | Owning boundary | Required lock/fence | Online result | Failure behavior |
|---|---|---|---|---|
| absent -> first `STAGING` | `DocumentGenerationRepository.allocate` | lifecycle row `FOR UPDATE`; PostgreSQL transaction | none | allocation rollback leaves no partial authority |
| `ACTIVE G(n)` + allocate `G(n+1)` | `DocumentGenerationRepository.allocate` | lifecycle row `FOR UPDATE`; stale retention claim cleared under same row authority | `G(n)` stays online; `G(n+1)` offline | failure/retry cannot hide `G(n)` |
| `STAGING G(n+1)` -> publish | `GenerationPublicationService` | lifecycle row `FOR UPDATE`; exact generation row `FOR UPDATE`; idempotency fence | atomic `G(n)` off / `G(n+1)` on | stale publisher returns superseded/fails closed |
| `STAGING` -> `FAILED` | generation failure/recovery | exact generation identity; lifecycle update generation-fenced | previous published generation remains online when present | no resurrection of failed staging payload |
| `ACTIVE` -> TTL-invalid | canonical eligibility predicate using `clock_timestamp()` | PostgreSQL time | immediately offline | physical cleanup may lag |
| TTL-invalid -> `DELETE_PENDING` | `RetentionClaimRepository` | `FOR UPDATE SKIP LOCKED`, claim id/generation/worker/lease | remains offline | stale contender cannot own claim |
| `DELETE_PENDING` -> `DELETING` -> `DELETED` | `ChunkRetentionService` | generation-scoped claim fence + transaction | remains offline | lease/fence loss aborts destructive commit |
| valid graph endpoints -> graph mutation | `GraphMutationLocks` | lifecycle `FOR SHARE` -> generation `FOR SHARE` -> canonical advisory locks | mutation allowed only while both endpoints valid | invalid endpoint fails closed |
| Dream source/candidate observe | Dream repositories | live Dream lease/token plus canonical endpoint eligibility | candidate usable only while both endpoints valid | lifecycle loss rejects write or filters stored row |
| graph/Dream retirement | retirement path | retirement locks permit already-invalid authority rows | only removes derived state | cannot re-enable authority |

### Forbidden transitions

The following transitions are contract violations:

- `STAGING -> online` without atomic publication;
- `RETIRING/RETIRED/FAILED -> PUBLISHED` by retry or worker restart;
- `G(n+1)` staging causing valid `G(n)` to become unavailable;
- Dream or graph local state making an ineligible generation usable;
- physical cleanup of `G(n)` mutating payload keyed to `G(n+1)`;
- lease renewal or stale claim reuse after fencing authority is lost.

### Document TTL

When `expires_at <= database_now`, the document becomes immediately ineligible for:

- retrieval and final context;
- graph traversal and graph mutation;
- graph learning/reinforcement;
- Dream candidate discovery/admission;
- query-memory reuse when source eligibility depends on the expired generation;
- identifier/reference/concept expansion through the expired generation.

The retention worker then performs asynchronous physical purge using existing claim/lease/fencing semantics.

### Generation lifecycle

Publishing `G(n+1)` atomically makes `G(n+1)` the only online generation and makes `G(n)` logically superseded even if old payload remains physically present until cleanup.

Starting ingestion for `G(n+1)` does not change online authority when `G(n)` is already published. Replacement staging is represented by the generation row, while document lifecycle remains `READY` for the older published generation.

Invariant: at most one generation of a document may satisfy `generationOnline(...)`.

### Parent/child lifecycle

Children inherit authority from their owning generation. A child cannot outlive its parent generation.

If local child expiry is introduced, effective expiry is the minimum of parent and child expiry. Local child state can only narrow, never widen, parent eligibility.

### Adaptive graph decay

Graph lifecycle distinguishes two causes:

1. **authority invalidation** — source or target generation becomes ineligible; edge becomes unusable immediately;
2. **semantic decay** — both endpoints remain eligible, but evidence/usage decays according to graph policy.

Authority invalidation always dominates graph score, temperature or decay state.

### Graph TTL

Graph TTL is derived, not authoritative. Effective edge eligibility requires both endpoints to be lifecycle-valid plus any graph-local policy.

Conceptually:

```text
edgeEligible = sourceGenerationOnline
            AND targetGenerationOnline
            AND graphLocalPolicyAllows
```

A stale row may remain in storage temporarily, but it must not be usable online.

### Dream forgetting

Dream never decides whether authoritative knowledge is alive. Dream candidates are eligible only while all referenced generations are eligible.

Candidate observation re-proves both endpoint generations together with the live Dream lease/token. Reads of `ACTIVE` candidate rows also join both endpoint lifecycle/generation authorities. Therefore a physically retained stale row is immediately non-usable before asynchronous retirement/compaction changes its stored state.

Dream state must never resurrect a superseded, expired, retiring or retired generation.

## Mandatory invariants

| ID | Invariant |
|---|---|
| INV-01 | Expired document never enters retrieval/final context. |
| INV-02 | Expired document never participates in graph expansion or mutation. |
| INV-03 | Expired document never produces or retains usable Dream candidates. |
| INV-04 | Only `published_generation` may be online-visible. |
| INV-05 | Publication of a replacement generation atomically invalidates the previous generation for online use. |
| INV-06 | A child can never be online-eligible after its parent generation becomes ineligible. |
| INV-07 | A graph edge can never be online-eligible after either endpoint becomes ineligible. |
| INV-08 | Dream cannot resurrect invalid graph/generation state. |
| INV-09 | Physical cleanup may lag logical invalidation; logical invalidation may not lag physical cleanup. |
| INV-10 | Retry, restart or lease loss cannot resurrect retired/ineligible state. |
| INV-11 | Purging `G(n)` cannot delete or mutate payload belonging to `G(n+1)`. |
| INV-12 | Distributed lifecycle decisions use PostgreSQL time and fencing, not pod-local time. |

## Stage 1 — Lifecycle contract + invariant matrix

### Scope

- inventory every lifecycle predicate in Retrieval, Query Memory, Reference/Identifier/Concept expansion, Generation publication, Retention, Adaptive Graph and Dream;
- map every current state to the semantic lifecycle states;
- document allowed and forbidden transitions;
- document authority hierarchy and failure semantics;
- define PostgreSQL-time and transaction/fencing rules;
- define logical invalidation versus physical purge semantics.

### Deliverables

- this document promoted from `TARGET` to `CURRENT` only after implementation;
- machine-readable or code-level invariant names for tests/metrics where practical;
- lifecycle transition matrix;
- negative-case matrix covering stale claims, stale generations and endpoint invalidation.

### Exit criteria

No subsystem has an undocumented interpretation of `READY`, `ACTIVE`, `PUBLISHED`, `expires_at`, `published_generation`, supersession or forgetting.

## Stage 2 — Canonical eligibility boundary

### Scope

Remove semantic duplication between Retrieval, Graph and Dream. Centralize the authoritative online-generation boundary without creating a new competing lifecycle store.

Implemented shape:

```text
PublishedLifecycleSql.eligible(lifecycleAlias)
PublishedLifecycleSql.publishedGenerationEligible(lifecycleAlias, generationAlias)
```

The first form preserves compatibility for repositories that expose only the lifecycle alias and verifies the referenced `PUBLISHED` generation through an indexed `EXISTS`. The second is used when the hot path already joins the exact generation and avoids another query or redundant authority store.

### Required consumers

- retrieval pre-filter/final revalidation;
- query-memory source eligibility;
- graph mutation locks;
- graph expansion/search;
- Dream source/candidate selection;
- reference/identifier/concept paths where generation eligibility is relevant.

### Constraints

- no permissive fallback when lifecycle state cannot be verified;
- no Redis/cache authority;
- no pod-local-time decisions;
- preserve ACL/access-level checks as an independent mandatory boundary;
- avoid introducing extra JDBC round trips on hot retrieval paths where a joined predicate can prove the same condition.

### Exit criteria

Predicate drift is removed. All required consumers use the canonical contract or a mechanically equivalent shared query boundary covered by contract tests.

## Stage 3 — Propagation

### Scope

Make invalidation propagation explicit and monotonic:

```text
Document expiry/deletion
  -> generation ineligible
    -> children ineligible
      -> graph endpoints/edges ineligible
        -> Dream candidates ineligible
```

Generation supersession follows the same path for the previous generation.

### Required behavior

- publish `G(n+1)` fences `G(n)` from online paths in the publication transaction;
- staging `G(n+1)` does not hide a valid already-published `G(n)`;
- child/projection/vector/reference/identifier rows may physically remain until purge, but cannot pass eligibility;
- graph and Dream reject invalid endpoints even if their own local rows still exist;
- cleanup workers remain generation-scoped and fenced;
- stale cleanup claims fail closed;
- re-ingestion after expiration/deletion creates a new valid generation without reviving stale derived state.

### Exit criteria

There is no valid execution path in which superseded/expired authority remains usable because cleanup or maintenance has not yet run.

## Stage 4 — Failure-injection proof

The following races are release-blocking tests for this work.

| Scenario | Required proof |
|---|---|
| TTL expires during query | Final context revalidation rejects the source even if it passed an earlier retrieval phase. |
| Replacement staging | `G(n)` remains online while `G(n+1)` is `STAGING`. |
| Publication race | Only the newest successfully published generation remains online; stale publisher is fenced/superseded. |
| Retention lease loss | Stale worker cannot continue or commit destructive cleanup after lease/fence loss. |
| Dream race | Candidate discovered before invalidation cannot be persisted as usable or applied after authority becomes invalid. |
| Graph mutation race | Edge mutation fails closed if source/target generation becomes invalid before commit. |
| `G(n+1)` published while purging `G(n)` | Cleanup remains generation-scoped and cannot remove or corrupt `G(n+1)`. |
| Pod clock skew | Eligibility/lease behavior is unchanged because database time is authoritative. |
| Worker retry/restart | Retired/ineligible state is not resurrected and cleanup remains idempotent/fenced. |

Tests should use real PostgreSQL transactions where the behavior depends on locking, database time, SKIP LOCKED, leases, fencing or concurrent publication/cleanup.

## Acceptance criteria

This gap is closed only when all of the following are true:

1. Document TTL, generation lifecycle, parent/child lifecycle, adaptive graph decay, graph TTL and Dream forgetting are described by this single authority model.
2. Retrieval, Graph and Dream cannot diverge on whether a generation is online-eligible.
3. Supersession and expiry invalidate online use synchronously; cleanup may remain asynchronous.
4. Derived state cannot outlive authority logically.
5. Cleanup is generation-scoped and fenced against newer publication.
6. All twelve mandatory invariants have positive and negative automated coverage.
7. The required race scenarios above pass repeatedly under integration/failure-injection execution.
8. Existing quality gates are not weakened to make the change pass.
9. Current-state documentation is updated in the same PR and this document is promoted to `CURRENT` or merged into the authoritative runtime architecture document.

## Current implementation progress

The branch now implements the first correctness hardening slice:

- replacement staging preserves an existing published generation as online authority;
- canonical lifecycle SQL proves both lifecycle state and physical `PUBLISHED` generation state;
- final retrieval eligibility, Query Memory, semantic neighbor search, Dream source enumeration and graph mutation lifecycle checks use the shared semantics;
- Dream candidate observation and `ACTIVE` reads re-prove both endpoint generations;
- real PostgreSQL tests cover replacement staging, non-`PUBLISHED` generation fail-closed behavior and Dream lifecycle invalidation.

The document remains `TARGET` until Stage 4 is fully mapped to INV-01..INV-12 and an exact final branch head passes the required CI/quality workflows.

## Non-goals

- replacing PostgreSQL lifecycle authority with Redis or an external coordinator;
- redesigning ingestion payloads unrelated to lifecycle correctness;
- adding independent TTL clocks to every derived table;
- making physical deletion synchronous with request paths;
- weakening graph/Dream functionality by disabling it instead of fixing lifecycle correctness.

## Implementation order

Implement strictly in this order:

1. Stage 1 contract and executable invariant inventory.
2. Stage 2 canonical eligibility boundary and consumer migration.
3. Stage 3 invalidation/supersession propagation hardening.
4. Stage 4 failure-injection tests and evidence.
5. Update current architecture/readiness documentation and declare the lifecycle gap closed only after the full suite is green.
