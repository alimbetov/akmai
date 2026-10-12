# Production readiness hardening audit — 2026-10-12

Classification: **CURRENT HARDENING LEDGER / RELEASE QUALIFICATION NOT YET COMPLETE**

Audited base: `main@57c5941166a9bd0bac67b1e273b4f908ee27e167`  
Hardening branch evidence head before this document: `quality/production-readiness-hardening-v1@590a46fb6da226dc2f00589f9e6ab7e037e88c8e`

This document supersedes `post-v1.1-readiness-2026-10-08.md` for current open-gap classification. The older audit remains a historical snapshot and is still useful for traceability.

## Executive decision

AkmAI is code-capable for controlled internal/pilot production use, but is not yet a formally release-qualified production product.

Current classification:

- **GO** — local development, integration, controlled internal/on-prem pilot;
- **CONDITIONAL GO** — production candidate after deployment-specific qualification;
- **NO-GO** — broad externally claimed production release until the release blockers below are closed on one exact candidate SHA/tag.

The remaining work is now primarily qualification/governance/evidence, not another core RAG redesign.

## What changed since the 2026-10-08 audit

The historical issue tracker state and runtime state have been reconciled substantially:

- issues #36-#41 are closed/completed and must no longer be presented as open production defects;
- unified lifecycle authority is implemented across retrieval, final context, Query Memory source reuse, adaptive graph endpoints and Dream candidates;
- TTL/published-generation PostgreSQL integration evidence now exists;
- retrieval PostgreSQL timeout and repeated connection-reuse evidence exists;
- request-body streaming/unknown-length enforcement is covered;
- non-local security startup validation is covered;
- re-embedding, retention, Dream, graph mutation and reconciliation have PostgreSQL ownership/fencing/failure evidence;
- Query Memory / learning events now have a dedicated service contract and direct source-lifecycle integration coverage on this hardening branch.

These changes close evidence/documentation gaps. They do not, by themselves, establish release qualification.

## Current executable evidence matrix

| Boundary | Current evidence | Classification |
|---|---|---|
| Published lifecycle / TTL | `PostgresPublishedLifecycleEligibilityIntegrationTest` proves expired rejection across retrieval types, final-context expiry race, non-`READY`, non-`PUBLISHED`, generation and ACL fencing | IMPLEMENTED + DIRECT PG EVIDENCE |
| Query Memory source reuse | `QueryMemorySourceEligibilityIntegrationTest` proves live acceptance plus expired, mixed-source, malformed, ACL, superseded-generation, non-`READY` and empty-scope rejection | IMPLEMENTED ON HARDENING BRANCH; EXACT-HEAD GATE PENDING |
| Query Memory anti-poisoning | `QueryMemoryAntiPoisoningIntegrationTest` proves duplicate suppression, independent support and policy namespace isolation | IMPLEMENTED |
| Learning / feedback persistence | `SelfOptimizingRagPersistenceIntegrationTest`, `RagFeedbackAntiPoisoningIntegrationTest` prove persistence, revision refresh, TTL cleanup, request/feedback idempotency and anti-poisoning | IMPLEMENTED |
| Retrieval executor cancellation | `ParallelRetrievalExecutorCancellationTest`, `ParallelRetrievalExecutorRecoveryTest` prove owned-task cancellation and worker reuse | IMPLEMENTED |
| PostgreSQL retrieval timeout/recovery | `RetrievalTransactionTimeoutIntegrationTest` uses real `pg_sleep(5)` and proves timeout plus connection reuse after repeated timeout cycles | IMPLEMENTED + DIRECT PG EVIDENCE |
| Request byte boundary | `RequestBodySizeFilterTest`, `RequestBodySizeFilterTransportIntegrationTest` cover declared-size and unknown/chunked transport enforcement | IMPLEMENTED |
| Non-local startup security | `SecurityStartupValidatorTest` covers fail-closed unsafe configuration | IMPLEMENTED |
| Unified lifecycle races | `RetentionReingestionRaceTest`, graph mutation lifecycle race tests, retention lease-expiry rollback and lifecycle completion ledger | IMPLEMENTED |
| Multi-pod ownership | Dream, re-embedding, retention, reconciliation, graph maintenance and audit scheduler ownership/fencing tests | IMPLEMENTED |

## Test coverage findings in this hardening pass

### Resolved: direct Query Memory lifecycle proof

Before this branch, `QueryMemorySourceEligibility` was exercised by mock-level consumer tests but did not have a direct PostgreSQL integration test of its canonical lifecycle SQL.

This branch adds `QueryMemorySourceEligibilityIntegrationTest` and proves that historical memory cannot reuse:

- expired sources;
- a mixed source set where one source is invalid;
- malformed source references;
- sources outside caller ACL;
- superseded generations;
- a lifecycle that left `READY`;
- any source when caller ACL scope is empty.

This is the main new runtime evidence added by the first pass.

### Reclassified: retrieval resource recovery was already covered

The 2026-10-08 audit requested real PostgreSQL timeout/resource-recovery evidence. Current code already contains it:

```text
RetrievalTransactionTimeoutIntegrationTest
  -> SELECT pg_sleep(5)
  -> bounded retrieval transaction timeout
  -> DataAccessException inside configured bound
  -> SELECT 1 succeeds afterwards
  -> repeat timeout/reuse cycle three times
```

Together with `ParallelRetrievalExecutorRecoveryTest`, this closes the old evidence gap. Do not add duplicate timeout tests unless a new resource-boundary defect is found.

### Reclassified: TTL real-row proof was already covered

The old audit requested a real expired PostgreSQL row test. `PostgresPublishedLifecycleEligibilityIntegrationTest` now provides this directly and also proves final-context expiry races and exact published generation/ACL fencing.

The old #36 evidence gap is therefore no longer current.

## Documentation truthfulness findings

### D1 — Query Memory / learning contract was missing

`docs/services/service-inventory.md` previously listed Query Memory / learning events as `TODO` even though the implementation and several integration tests already existed.

This branch adds `docs/services/query-memory-learning.md` and changes the inventory entry to `DRAFT`.

`DRAFT` is intentional. The contract must not become `VERIFIED` until the same exact documentation/code head passes required gates.

### D2 — top-level docs baseline is stale

`docs/README.md` still states synchronization against `main@c8ca0d...`, which predates the merged unified lifecycle contract. It also calls unified lifecycle `TARGET` despite completion evidence and merge into `main`.

Required remediation in this branch:

- synchronize the documentation baseline to current `main`/hardening evidence;
- classify unified lifecycle as current implementation rather than target work;
- make this hardening ledger the current readiness entry point;
- preserve the 2026-10-08 audit explicitly as historical evidence.

### D3 — top-level README still tells readers to close #36-#41

The current README release checklist/next milestones still mention closing/reconciling issues #36-#41 even though current audit index already classifies them as closed/completed historical trackers.

Required remediation:

- replace that task with retaining current closure/evidence mapping;
- keep actual release blockers prominent.

## Remaining production-release blockers

These are not hidden code defects; they are explicit production qualification requirements.

### R1 — approved immutable RAG baseline

The release workflow expects:

`benchmarks/rag-benchmark-v1/baselines/approved.json`

The approved baseline is still absent. Until a reviewed baseline is committed, release-to-release quality comparison is not immutable/reproducible.

### R2 — integrated live release qualification

For the exact release candidate SHA/tag, retain one successful run of `.github/workflows/rag-v1.1-release-qualification.yml` with:

```text
quality = success
performance = success
grounding = success
qualified = true
```

Normal CI is necessary but not a substitute.

### R3 — target-hardware SLO/capacity evidence

Define and retain p50/p95/p99, throughput, saturation and capacity evidence on the intended CPU/GPU/RAM/PostgreSQL/Ollama topology. GitHub Actions timings are not production SLO evidence.

### R4 — default-branch governance

At audited base `main@57c5941...`, GitHub reports `protected=false` and no enforced required checks on the branch.

Formal release governance must require PR-based changes and the agreed CI/quality/storage/image gates, with force-push/deletion restrictions appropriate to repository ownership.

### R5 — external-validity corpus

The controlled synthetic benchmark is a useful deterministic regression asset, not sufficient evidence for broad legal/medical/technical accuracy claims. Maintain a separate versioned, human-reviewed representative corpus before making those claims.

## Current engineering priorities

Do not expand the architecture while these gates remain open. The recommended order is:

1. finish this test/documentation truthfulness pass;
2. obtain exact-head green CI, Retrieval Quality Gate, Retrieval Storage Final Benchmark and Production Image Build;
3. promote only fully evidenced service contracts from `DRAFT` to `VERIFIED`;
4. establish/review the immutable approved benchmark baseline;
5. run integrated live release qualification on one exact candidate;
6. qualify target-hardware SLO/capacity;
7. enforce default-branch governance;
8. add/retain the human-reviewed external-validity corpus.

## Non-goals

This hardening line should not add:

- Redis/Kafka merely for coordination;
- a new graph database;
- new retrieval lanes without a demonstrated quality gap;
- uncontrolled online learning;
- broad semantic-corpus expansion without measured utility;
- lower thresholds or weaker quality gates to obtain green CI.

## Definition of done for this branch

This branch is complete when:

- every newly discovered material coverage gap is either backed by a concrete test or explicitly classified with rationale;
- current service documentation no longer has known `TODO` entries for implemented critical processes;
- stale current-state documentation is corrected without rewriting historical snapshots;
- exact branch head passes CI, Retrieval Quality Gate, Retrieval Storage Final Benchmark and Production Image Build;
- only then are eligible service contracts promoted to `VERIFIED`.

Release qualification remains a separate final gate even after this branch is merge-ready.
