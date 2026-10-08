# Quality-stable baseline audit — 2026-10-08

Branch: `quality/quality-stable-baseline`  
PR: #66  
Scope: post-DREAM stabilization; no DREAM-6 or retrieval-policy feature expansion.

## Declaration rule

`quality-stable baseline` is a binary release-quality claim. It MUST NOT be declared until the same final commit SHA satisfies every mandatory item in this document and every required GitHub Actions gate is green.

Required exact-SHA workflows:

- CI
- Retrieval Quality Gate
- Retrieval Storage Final Benchmark
- Production Image Build

A prior green SHA does not qualify a later commit.

## Workstream ledger

| ID | Workstream | Current status | Required evidence for VERIFIED |
| --- | --- | --- | --- |
| QS-01 | Finish/merge Adaptive Graph Dream | VERIFIED | PR #63 merged into `main`; stabilization starts from post-DREAM main |
| QS-02 | Code duplication inventory | IMPLEMENTING | graph mutation duplication catalogued; remaining transaction/lease/compatibility duplication reviewed |
| QS-03 | Extract graph locking/lifecycle/timeout/fencing primitives | IMPLEMENTING | all graph mutation paths use shared primitives; behavior-preserving integration tests green |
| QS-04 | Reduce JDBC round-trips | IMPLEMENTING | semantic pair admission uses one read round-trip; remaining high-frequency paths measured before/after |
| QS-05 | Transaction-boundary audit | IMPLEMENTING | no network/model I/O held inside graph DB mutation transactions; short transactions have explicit bounds; nested transaction risks documented/tested |
| QS-06 | Profiler/benchmark pass | IMPLEMENTING | storage benchmark green on final SHA; graph mutation/query-count and concurrency evidence retained |
| QS-07 | Connection-pool + SQL/index audit | IMPLEMENTING | pool concurrency envelope documented; no starvation under constrained-pool test; representative graph SQL has acceptable EXPLAIN plans |
| QS-08 | Dead code / compatibility scaffolding removal | OPEN | every removed path proven unused; intentional migration/fail-closed compatibility retained and documented |
| QS-09 | Integration/failure-injection coverage | IMPLEMENTING | stale authority, lifecycle invalidation, timeout/rollback, lock contention and constrained-pool cases covered |
| QS-10 | Declare quality-stable baseline | BLOCKED | QS-01..QS-09 VERIFIED and all exact-SHA workflows green |

## Duplication inventory

### Closed / being closed

1. PostgreSQL transaction advisory graph-node locking was duplicated across online reinforcement, semantic ingestion seeding and Dream semantic apply. It is centralized in `GraphNodeLockManager` with canonical node order.
2. Graph lifecycle `FOR SHARE` validation was duplicated across the same writers. It is centralized in `GraphLifecycleGuard` while preserving the intentional policy split:
   - online reinforcement: published + ACTIVE;
   - semantic/Dream mutation: published + READY + ACTIVE + non-expired.
3. Semantic pair existence and per-source semantic-degree admission was independently implemented in semantic ingestion and Dream. It is centralized in `SemanticPairAdmissionRepository` and returned by one JDBC query.
4. Dream graph mutation transaction timeout construction is centralized in `GraphTransactionExecutor` so the timeout applies to the actual short mutation transaction, not to an entire Dream run.
5. Dream graph fencing validation is centralized in `DreamAuthorityGuard`; SQL writes retain their own lease-token predicate as a final write-time fence.

### Still under review

- Dream lease/checkpoint/run fencing vs re-embedding lease fencing: similar distributed-authority semantics, but table/state models differ. Do not generalize until a shared abstraction removes real duplication without hiding SQL authority rules.
- repeated symmetric graph upsert SQL between semantic seed and Dream apply;
- general transaction-template wrappers across retrieval, lifecycle, retention and vector publication;
- legacy/unscoped RAG memory and provenance paths: some are intentional migration/fail-closed behavior and are not dead code by name alone.

## Transaction-boundary invariants

The final baseline MUST satisfy:

1. No full Dream cycle is enclosed in one database transaction.
2. Graph mutation transactions contain only database locking/validation/admission/mutation work; no ANN, embedding, LLM or other network/model calls.
3. Dream fencing is validated inside the mutation transaction.
4. Lifecycle eligibility is locked/validated before graph mutation.
5. Graph node locks are transaction-scoped and acquired in canonical order.
6. Transaction timeout is applied to the actual transaction boundary.
7. Failure after the first logical mutation of a symmetric operation must roll back the entire pair mutation.
8. Lease loss, query timeout, connection failure or rollback must not be converted into semantic negative evidence.

## JDBC round-trip budget

For semantic pair admission after lifecycle/node locks:

- before stabilization: pair existence + first degree + second degree = 3 JDBC read round-trips;
- target baseline: one `SemanticPairAdmissionRepository.load(...)` round-trip.

Directional mutation round-trips remain a review item. Combining them is allowed only if bidirectional atomicity, fencing and learned-evidence preservation remain explicit and integration-tested.

## Connection-pool audit

Runtime currently exposes concurrent ingestion/retrieval/retention workers and Dream DB work. The final baseline must not rely accidentally on an undocumented Hikari default.

Before choosing a pool size, derive the database concurrency envelope and verify it with a deliberately constrained Hikari pool. Prefer a validated capacity invariant over an arbitrary large pool. At minimum the test must prove progress under concurrent graph/retrieval/lifecycle work without a connection-starvation cycle.

## SQL/index audit

`knowledge_chunk_association` is partitioned by ACL and source hash. Existing source read/maintenance and target indexes are useful, but semantic admission adds this predicate:

```sql
semantic_similarity IS NOT NULL
AND band <> 'DECAYED'
AND graph_version = ?
```

Do not add a semantic-specific index solely from inspection. First retain `EXPLAIN (ANALYZE, BUFFERS)` or deterministic plan evidence on representative cardinality. Add a partial/index-key change only if it materially improves the measured plan without unacceptable write amplification.

## Compatibility/dead-code rule

A symbol containing `legacy`, `compatibility` or an old namespace is not automatically dead. Removal requires all of:

1. no production call path;
2. no supported persisted row/migration depends on it;
3. no fail-closed isolation behavior depends on it;
4. tests prove removal does not make old/unscoped data visible to an active policy namespace.

## Failure-injection matrix

Mandatory graph-focused cases before QS-09 can be VERIFIED:

- stale Dream fencing token rejected inside mutation transaction;
- lease expires/lost before write: zero graph mutation committed;
- lifecycle becomes ineligible: mutation rejected;
- transaction timeout/forced exception after first directional write: pair remains atomic;
- concurrent reversed pair writers do not deadlock due to canonical lock order;
- semantic degree limit remains race-safe under node locks;
- constrained connection pool makes progress under concurrent graph mutations;
- apply-disabled gate performs no mutation work;
- infrastructure failure is not recorded as semantic negative evidence.

## Performance gate

The baseline is not a promise that every query is globally optimal. It means:

- no regression in the existing Retrieval Storage Final Benchmark on the final SHA;
- targeted graph mutation measurements show the admission read reduction;
- no newly introduced unbounded scan or unbounded transaction;
- SQL plans preserve ACL/source partition pruning for online graph lookup;
- pool wait/pending behavior is bounded under the accepted concurrency envelope.

## Final acceptance

Declare **quality-stable baseline** only when:

```text
QS-01 .. QS-09 = VERIFIED
AND CI(final_sha) = success
AND Retrieval Quality Gate(final_sha) = success
AND Retrieval Storage Final Benchmark(final_sha) = success
AND Production Image Build(final_sha) = success
AND PR #66 mergeable
```

After merge, record the merged commit SHA in this document or a retained result artifact. That SHA becomes the quality-stable baseline reference for subsequent DREAM-6 or feature work.
