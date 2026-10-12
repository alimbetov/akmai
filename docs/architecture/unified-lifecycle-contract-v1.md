# Unified lifecycle contract v1

Status: **CURRENT / IMPLEMENTED**

Current implementation baseline: merged by PR #96 into `main@57c5941166a9bd0bac67b1e273b4f908ee27e167`.

Completion/evidence ledger: [`../audit/unified-lifecycle-contract-v1-completion-2026-10-12.md`](../audit/unified-lifecycle-contract-v1-completion-2026-10-12.md).

The earlier pre-remediation audit is historical evidence only: [`../audit/unified-lifecycle-contract-v1-audit-2026-10-12.md`](../audit/unified-lifecycle-contract-v1-audit-2026-10-12.md).

## Purpose

AkmAI uses one lifecycle authority model for document retention, generation publication, parent/child visibility, retrieval eligibility, adaptive-graph endpoints and Dream candidates.

There is no second lifecycle store and no cache/Redis authority. PostgreSQL lifecycle/generation state, PostgreSQL time and fencing/transaction ownership remain authoritative.

The governing rule is:

```text
Document lifecycle
  -> Published generation
    -> Parent/child eligibility
      -> Chunk/vector/identifier/reference/concept eligibility
        -> Adaptive graph endpoint/edge eligibility
          -> Dream candidate eligibility
```

Derived state may remain physically present after invalidation, but it must become logically unusable immediately when its authority becomes ineligible.

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

`database_now` is PostgreSQL time (`clock_timestamp()` or an equivalent database-time expression). Pod/JVM clocks are not distributed lifecycle authority.

The executable SQL boundary is centralized in `PublishedLifecycleSql`:

- `eligible(lifecycleAlias)` — lifecycle predicate plus proof that the referenced generation is physically `PUBLISHED`;
- `publishedGenerationEligible(lifecycleAlias, generationAlias)` — equivalent joined form for hot paths that already join `knowledge_document_generation`;
- lifecycle-state-only form is used only where an explicit exact-generation locking step follows in the same transaction.

No caller may replace the canonical predicate with a weaker local interpretation.

## Semantic lifecycle states

```text
STAGING -> ACTIVE -> SUPERSEDED -> RETIRING -> RETIRED
               \---------------------> RETIRING
```

These are semantic states derived from existing authoritative rows, not a new persisted state machine.

| Authoritative state | Semantic state | Online? |
|---|---|---:|
| first generation `STAGING`, no published generation | STAGING | no |
| lifecycle `READY/ACTIVE`, unexpired, exact published generation `PUBLISHED` | ACTIVE | yes |
| `G(n)=PUBLISHED` while replacement `G(n+1)=STAGING` | G(n) ACTIVE; G(n+1) STAGING | G(n) only |
| replacement publication atomically moves authority to `G(n+1)` | G(n) SUPERSEDED/RETIRING; G(n+1) ACTIVE | G(n+1) only |
| TTL expired but payload still exists | RETIRING logically | no |
| lifecycle `DELETE_PENDING` / `DELETING` | RETIRING | no |
| generation `RETIRING`, `RETIRED`, `FAILED`, or lifecycle points elsewhere | SUPERSEDED/RETIRING/RETIRED | no |
| Dream/graph row whose endpoint lost authority | derived stale/retired logically | no |

## Publication and replacement

Starting replacement ingestion for `G(n+1)` must not hide a valid published `G(n)`.

While replacement staging is in progress:

```text
lifecycle_status = READY
retention_status = ACTIVE
published_generation = G(n)
generation = G(n+1)          // staging/current work identity
```

Only `published_generation` is online authority.

Publication owns the atomic authority flip. After successful publication, exactly one generation may satisfy `generationOnline(...)`.

A failed/stale publisher or retry must never resurrect a generation that is no longer authoritative.

## TTL and physical cleanup

TTL invalidation is synchronous at read/write eligibility boundaries:

```text
expires_at <= clock_timestamp()
=> immediately offline
```

The retention worker performs physical cleanup asynchronously after logical invalidation.

Therefore:

```text
logical invalidation may precede physical deletion
physical deletion must never precede authority/fencing proof
```

Retention claims are generation-scoped and use PostgreSQL ownership/lease/fencing semantics. A stale worker cannot continue destructive cleanup after losing authority.

Replacement staging and destructive retention are mutually fenced so cleanup of `G(n)` cannot mutate payload belonging to `G(n+1)`.

## Parent/child eligibility

Children inherit generation authority from their parent document/generation.

A child cannot remain online after its parent generation becomes ineligible. Any child-local eligibility rule may only narrow parent authority, never widen it.

Parent expansion resolves exact document/generation/chunk identities through current published projection eligibility before adding evidence.

## Retrieval and final context

All retrieval lanes and final context reuse obey the canonical published lifecycle boundary.

An item that was eligible during an early retrieval phase may still be rejected by final-context revalidation if its lifecycle changes before answer construction.

Required behavior includes:

- expired rows rejected;
- non-`READY` lifecycle rejected;
- non-`ACTIVE` retention rejected;
- non-`PUBLISHED` generation rejected;
- superseded generation rejected;
- ACL mismatch rejected;
- final context revalidation repeats authority checks to close TOCTOU races.

## Query Memory

Historical Query Memory is derived state. It cannot outlive its cited sources logically.

Before a historical observation reaches HyDE/query-memory prompt input, every cited source reference is revalidated against current projection, ACL and canonical published lifecycle state.

One invalid source invalidates that observation. A match with no surviving observations is dropped.

Detailed contract: [`../services/query-memory-learning.md`](../services/query-memory-learning.md).

## Adaptive graph

Graph eligibility is derived from endpoint authority:

```text
edgeEligible = sourceGenerationOnline
            AND targetGenerationOnline
            AND graphLocalPolicyAllows
```

Canonical endpoint filtering occurs before graph `ORDER BY/LIMIT`, so an expired/superseded high-score edge cannot consume adjacency budget or displace a valid edge before later materialization.

Mutation paths use canonical lock order:

```text
lifecycle authority
  -> exact generation row
    -> canonical transaction advisory node locks
      -> writer-specific mutation
```

Normal graph mutation requires both endpoints to remain eligible. Retirement paths may lock already-invalid rows in order to remove derived state, but may never re-enable authority.

## Dream candidates

Dream never decides whether authoritative knowledge is alive.

Candidate observation requires:

- live Dream lease/fencing token;
- source endpoint online eligibility;
- target endpoint online eligibility.

Lifecycle invalidation and lease loss are distinct outcomes:

- endpoint invalidation is a local candidate skip/retirement condition;
- actual Dream authority loss is fatal to the current Dream owner/run.

Stored `ACTIVE` candidate rows are revalidated on read; physical state cannot resurrect an invalid endpoint.

## Mandatory invariants

| ID | Invariant |
|---|---|
| INV-01 | Expired document never enters retrieval/final context. |
| INV-02 | Expired document never participates in graph expansion or mutation. |
| INV-03 | Expired document never produces or retains usable Dream candidates. |
| INV-04 | Only `published_generation` may be online-visible. |
| INV-05 | Replacement publication atomically invalidates the previous online generation. |
| INV-06 | A child cannot remain online after its parent generation becomes ineligible. |
| INV-07 | A graph edge cannot remain online after either endpoint becomes ineligible. |
| INV-08 | Dream cannot resurrect invalid graph/generation state. |
| INV-09 | Physical cleanup may lag logical invalidation; logical invalidation may not lag physical cleanup. |
| INV-10 | Retry, restart or lease loss cannot resurrect retired/ineligible state. |
| INV-11 | Purging `G(n)` cannot delete or mutate payload belonging to `G(n+1)`. |
| INV-12 | Distributed lifecycle decisions use PostgreSQL time and fencing, not pod-local time. |

## Allowed transition matrix

| Transition | Owning boundary | Authority / fence | Result |
|---|---|---|---|
| absent -> first STAGING | `DocumentGenerationRepository.allocate` | lifecycle row transaction | no online generation |
| ACTIVE G(n) + allocate G(n+1) | generation allocation | lifecycle row authority | G(n) remains online |
| STAGING G(n+1) -> publish | `GenerationPublicationService` | lifecycle + exact generation locks/fence | atomic G(n) off / G(n+1) on |
| STAGING -> FAILED | failure/recovery path | exact generation identity | older published generation remains online if present |
| ACTIVE -> TTL-invalid | canonical eligibility | PostgreSQL time | immediately offline |
| TTL-invalid -> DELETE_PENDING/DELETING | retention claim/service | claim id + generation + worker + lease | remains offline |
| valid endpoints -> graph mutation | `GraphMutationLocks` | lifecycle/generation/advisory locks | mutation allowed while both remain valid |
| Dream candidate observation | Dream repositories | Dream token + both endpoint authorities | persist only when all fences hold |
| invalid derived state -> retirement | retirement paths | retirement locks/fencing | derived state removed only |

## Forbidden transitions

The following are contract violations:

- STAGING becoming online without atomic publication;
- RETIRING/RETIRED/FAILED becoming `PUBLISHED` by retry/restart;
- staging `G(n+1)` making valid `G(n)` unavailable;
- Query Memory, graph or Dream making an expired/superseded generation usable;
- purge of `G(n)` mutating `G(n+1)` payload;
- stale retention/Dream/re-embedding authority continuing after fencing loss;
- using JVM time as distributed lifecycle/lease authority.

## Failure and race evidence

The current implementation is backed by real PostgreSQL/failure tests including:

- `PostgresPublishedLifecycleEligibilityIntegrationTest`
  - expired rows rejected across retrieval types;
  - final-context expiry race;
  - `INGESTING` / non-`PUBLISHED` / generation / ACL fencing.
- `RetentionReingestionRaceTest`
  - replacement staging keeps current published generation online;
  - retention/re-ingestion ownership conflicts.
- `ConcurrentGenerationPublicationIntegrationTest`
  - publication concurrency/stale publisher behavior.
- `RetentionLeaseExpiryIntegrationTest`
  - lease expiry after destructive work begins rolls back the cleanup transaction.
- adaptive graph mutation integration tests
  - endpoint invalidation races;
  - canonical locking/rollback/no-half-edge semantics.
- `DreamSemanticRetirementIntegrationTest`
  - invalid endpoint makes stored candidate immediately unusable;
  - lifecycle race cannot persist usable candidate;
  - stale token cannot commit retirement.
- `QueryMemorySourceEligibilityIntegrationTest`
  - historical source reuse cannot bypass TTL, ACL, supersession or lifecycle readiness.

The detailed INV-01..INV-12 evidence mapping is retained in the completion ledger rather than duplicated here.

## Positive cases

1. A valid `READY/ACTIVE/PUBLISHED` unexpired generation is retrievable under an authorized ACL.
2. Replacement staging preserves the older published generation until atomic cutover.
3. Publication makes the new generation visible and the prior generation unusable in one authority transition.
4. Physical cleanup may run later without exposing stale payload meanwhile.
5. Graph/Dream/Query Memory may use derived state only while underlying generations remain eligible.

## Negative cases

1. TTL expires between retrieval and final context: final context rejects the source.
2. A lifecycle row points to a non-`PUBLISHED` generation: fail closed.
3. A graph endpoint becomes invalid before mutation: transaction fails/skips without half-edge state.
4. A Dream endpoint becomes invalid before observation: candidate is not persisted as usable; the run does not misclassify that local invalidation as lease loss.
5. Retention loses lease after destructive work begins: the transaction rolls back.
6. A stale generation remains physically present: canonical read boundaries still reject it.
7. A stale worker/retry cannot republish or resurrect retired authority.

## Operational rules

- Do not introduce a second lifecycle authority store.
- Do not weaken `PublishedLifecycleSql` predicates to recover performance; optimize joins/indexes while preserving semantics.
- Do not remove final-context revalidation merely because earlier retrieval was filtered.
- Keep lifecycle/generation/advisory lock ordering stable across graph writers.
- Use PostgreSQL time for distributed expiry/lease decisions.
- Treat physical cleanup lag as an operational concern, not permission to keep stale rows logically usable.

## Verification status

The lifecycle implementation and completion evidence are merged into `main` through PR #96. This contract is therefore `CURRENT`.

`CURRENT` describes implementation state. It does **not** mean the whole AkmAI release is production-qualified; benchmark baseline, integrated live qualification, target-hardware SLO evidence and repository governance are tracked separately in the current production-readiness audit.
