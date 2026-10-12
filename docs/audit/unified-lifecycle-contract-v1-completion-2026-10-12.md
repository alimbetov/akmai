# Unified lifecycle contract v1 — completion evidence

Audit date: 2026-10-12

Implementation evidence ref: `feature/unified-lifecycle-contract-v1@a24f073ad149ebe3f6023ea07a8450474f99d6e7`

Baseline: `main@c8ca0d9263e2b481ef6c669c8174276160e7a769`

Current contract: `docs/architecture/unified-lifecycle-contract-v1.md`

Supersedes for current-state conclusions: `docs/audit/unified-lifecycle-contract-v1-audit-2026-10-12.md`, which remains an immutable historical audit of the pre-remediation branch state.

Classification: **CURRENT IMPLEMENTATION COMPLETION EVIDENCE / NOT A GENERAL RELEASE QUALIFICATION**

## Scope and decision

This document records the implementation state after remediation of the lifecycle defects identified in the earlier audit. It covers document TTL, generation publication and replacement staging, parent/child visibility, retention fencing, adaptive graph read/mutation eligibility, Dream candidate eligibility and the failure/race evidence required by the unified lifecycle contract.

The implementation is considered lifecycle-complete when the branch head that contains this documentation also passes the repository's exact-head verification workflows. Repository-wide release qualification, target-hardware SLO sign-off and unrelated governance controls remain separate decisions.

## Defect closure ledger

| ID | Previous severity | Resolution | Evidence |
|---|---:|---|---|
| LFC-01 | P0 | **CLOSED** — replacement `G(n+1)=STAGING` no longer sets a document with an existing published generation to `INGESTING`; `G(n)` remains online until atomic publication cutover. | `RetentionReingestionRaceTest.replacementStagingKeepsCurrentPublishedGenerationOnline` |
| LFC-02 | P0 | **CLOSED** — canonical online eligibility now proves lifecycle authority and an exact `knowledge_document_generation` row with `generation_status='PUBLISHED'`. | `PublishedLifecycleSql`; `PostgresPublishedLifecycleEligibilityIntegrationTest` negative generation-state cases |
| LFC-03 | P1 | **CLOSED** — Retrieval, Query Memory, semantic ANN, Dream source enumeration, graph mutation and graph lookup resolve to the shared lifecycle/generation SQL boundary. | `UnifiedLifecycleAuthorityArchitectureTest`; repository integration tests |
| LFC-04 | P1 | **CLOSED** — Dream write and ACTIVE-read boundaries require both endpoint generations to remain online; endpoint invalidation is distinguished from actual Dream lease loss. | `DreamSemanticRetirementIntegrationTest`; `DreamCandidateDiscoveryFailureModelTest` |
| LFC-05 | P1 | **CLOSED** — physical storage states, semantic states, transition ownership and online eligibility are mapped in the architecture contract. | `docs/architecture/unified-lifecycle-contract-v1.md` |
| LFC-06 | P1 | **CLOSED** — lifecycle races are mapped to executable PostgreSQL/failure-model tests, including graph invalidation while waiting on locks and retention lease expiry after destructive cleanup has started. | proof matrix below |
| LFC-07 | P2 | **CLOSED** — the lifecycle contract is indexed from the architecture documentation. | `docs/architecture/README.md` |

## Canonical authority boundary

The executable online-generation semantics are:

```text
lifecycle_status = READY
AND retention_status = ACTIVE
AND published_generation = requested generation
AND expires_at is null or expires_at > PostgreSQL clock_timestamp()
AND exact generation row exists
AND generation_status = PUBLISHED
AND access_level identities agree
```

`PublishedLifecycleSql` exposes three intentional forms:

- `eligible(lifecycle)` for lifecycle-only read queries; it includes an indexed exact-generation `EXISTS` proof;
- `publishedGenerationEligible(lifecycle, generation)` for queries that already join the generation row;
- `lifecycleEligible(lifecycle)` only for locking code that immediately acquires and validates the exact generation row separately.

The third form is deliberately narrower in API purpose and is protected by architecture tests so it cannot silently become a permissive online-read predicate.

## Invariant evidence matrix

| Invariant | Executable proof |
|---|---|
| INV-01 — expired document never enters retrieval/final context | `PostgresPublishedLifecycleEligibilityIntegrationTest` TTL cases and final-context revalidation tests |
| INV-02 — expired document never participates in graph expansion/mutation | `AdaptiveChunkGraphRepositoryTest.lookupFiltersIneligibleEndpointsBeforeLimit`; `AdaptiveGraphMutationFailureMatrixIntegrationTest.mutationFailsClosedWhenGenerationInvalidationCommitsWhileWaiting` |
| INV-03 — expired document never produces/retains usable Dream candidates | `DreamSemanticRetirementIntegrationTest.lifecycleInvalidationMakesStoredActiveCandidateImmediatelyUnusable`; endpoint-expiry observation test |
| INV-04 — only `published_generation` is online-visible | `PostgresPublishedLifecycleEligibilityIntegrationTest` tuple/generation fencing and non-PUBLISHED negative cases |
| INV-05 — replacement publication atomically invalidates previous online generation | `RetentionReingestionRaceTest.replacementStagingKeepsCurrentPublishedGenerationOnline`; existing `ConcurrentGenerationPublicationIntegrationTest` |
| INV-06 — child cannot outlive parent generation | `ParentContextExpansion` is constrained to `PublishedSearchProjectionReader.findPublishedByKeys`; `UnifiedLifecycleAuthorityArchitectureTest.parentExpansionCannotBypassPublishedProjectionBoundary`; final context is independently revalidated |
| INV-07 — edge cannot outlive either endpoint | `AdaptiveChunkGraphRepositoryTest.lookupFiltersIneligibleEndpointsBeforeLimit`; graph mutation failure matrix |
| INV-08 — Dream cannot resurrect invalid graph/generation state | Dream ACTIVE-read lifecycle filtering, endpoint-invalid observation classification, and `SemanticGraphPriorWriter` lifecycle-lock apply fence |
| INV-09 — physical cleanup may lag logical invalidation, never reverse | stored ACTIVE Dream row remains physically present but is absent from usable ACTIVE reads after lifecycle invalidation; TTL retrieval/graph filters are synchronous |
| INV-10 — retry/restart/lease loss cannot resurrect retired state | Dream stale-token tests, generation reconciliation failure matrix, retention stale-claim fencing |
| INV-11 — purging `G(n)` cannot touch `G(n+1)` | `RetentionReingestionRaceTest`: DELETE_PENDING claim invalidation before replacement staging, no retention claim while STAGING exists, allocation rejection after DELETING begins |
| INV-12 — distributed lifecycle uses PostgreSQL time | `UnifiedLifecycleAuthorityArchitectureTest.distributedLifecycleAuthorityUsesPostgresTime`; integration SQL uses `clock_timestamp()` for TTL/lease decisions |

## Required race/failure proof

| Scenario | Proof and result |
|---|---|
| TTL expires during query | Final exact lifecycle revalidation rejects the source. |
| `G(n+1)` starts while `G(n)` is published | `G(n)` remains online; `G(n+1)` is STAGING and cannot become online early. |
| Publication race | Existing concurrent-generation publication tests serialize publication through lifecycle/generation authority and prevent stale publication from becoming authoritative. |
| Retention claim exists before re-ingestion | Allocation locks the lifecycle row and invalidates the non-destructive DELETE_PENDING claim before creating the replacement STAGING generation. The old claim can no longer release/finalize. |
| Replacement is STAGING while retention scans | Retention claim SQL rejects any document that has a STAGING generation. |
| Destructive retention has entered DELETING | Replacement allocation fails closed; destructive cleanup and replacement allocation cannot overlap. |
| Retention lease expires after cleanup has started | `RetentionLeaseExpiryIntegrationTest` keeps the claim live at entry, enters the destructive vector phase, allows PostgreSQL lease time to expire, then proves the final fence returns STALE_CLAIM and transaction rollback restores lifecycle/generation/tombstone state. |
| Dream endpoint invalidates between verification and persistence | Repository raises `EndpointIneligibleException`; discovery releases reserved DB budget, skips activation/apply and continues the run. |
| Dream lease is actually lost | Repository raises `LostDreamAuthorityException`; stale owner remains a hard stop. |
| Graph endpoint invalidates while mutation waits | Real PostgreSQL concurrency test commits `RETIRED` while mutation waits on the generation lock; mutation fails closed and writes no association rows. |
| Invalid high-score graph edge precedes a valid edge | Graph lookup filters source/target lifecycle authority before ordering/limit, so the invalid edge cannot consume the result budget. |
| Pod clock skew | Lifecycle/retention/Dream distributed authority source guards prohibit JVM `now()` and require PostgreSQL `clock_timestamp()`. |

## Locking and transaction conclusions

The implementation preserves these global ordering rules:

```text
publication / retention / graph lifecycle authority
  -> lifecycle row
  -> exact generation row
  -> subsystem-specific advisory/fencing authority
```

Graph mutation keeps canonical node ordering and the explicit sequence:

```text
lifecycle FOR SHARE
-> generation PUBLISHED FOR SHARE
-> pg_advisory_xact_lock(node)
-> bilateral mutation
```

The lifecycle-only SQL fragment in the first lock does not repeat a generation lookup; the second lock is the authoritative generation proof and lock.

Retention cleanup keeps one transaction around lifecycle fence, generation validation, tombstone/payload work and final lease fence. If PostgreSQL time expires the lease before finalization, the transaction rolls back rather than committing partial cleanup.

## Performance/correctness trade-offs

No Redis, external coordinator or additional lifecycle store was introduced.

Read-side lifecycle proof is performed inside existing SQL statements. The graph read change adds lifecycle/generation joins before `ORDER BY/LIMIT`; this is intentional because post-limit filtering is semantically insufficient and can displace valid edges. Existing retrieval/storage benchmark workflows remain the performance regression gate.

Dream observation performs an additional lease lookup only on the zero-row rejection path. The normal successful observation remains one guarded DML statement. This preserves hot-path cost while distinguishing normal endpoint invalidation from distributed authority loss.

## Current-state documentation rule

The earlier `unified-lifecycle-contract-v1-audit-2026-10-12.md` remains a historical defect snapshot tied to its audited SHA. It must not be used as the current open-defect ledger after this completion record.

The authoritative runtime contract is `docs/architecture/unified-lifecycle-contract-v1.md`. It may be marked `CURRENT` once this implementation and the documentation tail are present together on an exact branch head that passes the repository verification gates.

## Exit decision

At the implementation evidence ref, all previously identified lifecycle code defects have concrete remediations and automated evidence. The remaining action is exact-head workflow qualification of the documentation-complete branch; that is evidence validation, not an additional lifecycle design or runtime defect.
