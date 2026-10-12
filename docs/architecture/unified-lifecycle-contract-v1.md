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

## Lifecycle states

The semantic state model is:

```text
STAGING -> ACTIVE -> SUPERSEDED -> RETIRING -> RETIRED
               \---------------------> RETIRING
```

Existing table-specific enums may remain where compatible. The contract above is the cross-subsystem meaning and must be mapped explicitly in code/documentation.

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

When authority is lost, candidate state must become non-usable immediately; physical deletion/compaction may follow asynchronously.

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

Preferred shape:

```text
LifecycleEligibility
LifecycleAuthorityRepository
```

or equivalent small primitives that reuse existing lifecycle/generation tables and PostgreSQL predicates.

The implementation may use a shared SQL fragment, repository method, view or another low-overhead mechanism, but all online paths must resolve to one semantics.

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
- child/projection/vector/reference/identifier rows may physically remain until purge, but cannot pass eligibility;
- graph and Dream must reject invalid endpoints even if their own local rows still exist;
- cleanup workers must remain generation-scoped and fenced;
- stale cleanup claims must fail closed;
- re-ingestion after expiration/deletion creates a new valid generation without reviving stale derived state.

### Exit criteria

There is no valid execution path in which superseded/expired authority remains usable because cleanup or maintenance has not yet run.

## Stage 4 — Failure-injection proof

The following races are release-blocking tests for this work.

| Scenario | Required proof |
|---|---|
| TTL expires during query | Final context revalidation rejects the source even if it passed an earlier retrieval phase. |
| Publication race | Only the newest successfully published generation remains online; stale publisher is fenced/superseded. |
| Retention lease loss | Stale worker cannot continue or commit destructive cleanup after lease/fence loss. |
| Dream race | Candidate discovered before invalidation cannot be applied after authority becomes invalid. |
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
7. The six required race scenarios above pass repeatedly under integration/failure-injection execution.
8. Existing quality gates are not weakened to make the change pass.
9. Current-state documentation is updated in the same PR and this document is promoted to `CURRENT` or merged into the authoritative runtime architecture document.

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
