# Quality-stable baseline v1

Status: **IN PROGRESS**

Branch: `quality/quality-stable-baseline-v1`
PR: #65

This document is the stabilization ledger after `feature/adaptive-graph-dream` was merged. A quality-stable baseline is declared only when the same final commit SHA satisfies all exit criteria below.

## Scope

1. code-duplication inventory;
2. graph locking/lifecycle/timeout/fencing primitives;
3. JDBC round-trip reduction;
4. transaction-boundary audit;
5. profiler/benchmark pass;
6. connection-pool, SQL and index audit;
7. dead-code and compatibility-scaffolding cleanup;
8. integration and failure-injection coverage;
9. final same-SHA CI/quality/benchmark/image gates.

## Correctness invariants

Graph mutation code must preserve these invariants:

- graph nodes are locked in deterministic canonical order;
- lifecycle eligibility is `READY + ACTIVE + published generation + non-expired`;
- ACL identity is preserved and cross-ACL graph pairs are rejected;
- Dream fencing is checked in the actual mutation statement inside the mutation transaction;
- a stale Dream owner cannot materialize either direction of a semantic pair;
- semantic prior writes never synthesize learned support/context/citation/query evidence;
- bidirectional graph changes are atomic at transaction scope;
- DB/ANN failures are not semantic negative evidence;
- no Dream run-wide transaction is allowed.

## Duplication inventory

### Graph mutation locking

Before stabilization, the following components independently implemented lifecycle row locking, canonical graph-node ordering and PostgreSQL advisory locks:

- `AdaptiveChunkGraphRepository`;
- `SemanticAssociationSeedRepository`;
- `SemanticGraphPriorWriter`.

Action: extracted `GraphMutationLocks` and migrated all three mutation paths to the common primitive.

### Lifecycle predicate

The normative lifecycle predicate appears in mutation and read paths, including Dream source selection, semantic ANN, semantic ingestion linking and Dream apply. Mutation-side validation is centralized in `GraphMutationLocks`. Read-side SQL remains explicit because aliases, joins, partition pruning and query-plan shape differ; forcing a string-fragment abstraction here would reduce SQL readability without reducing database work.

### Timeout conversion

Duration-to-JDBC-seconds conversion was local to semantic ANN while Dream transaction timeout used separate conversion logic.

Action: extracted `JdbcTimeouts` with positive-duration validation and ceil-to-seconds semantics. Semantic ANN and Dream writer reuse it.

### Dream fencing

Dream apply previously performed a preliminary authority probe and then separately used a lease predicate in mutation SQL.

Action: removed the preliminary probe. The authoritative fence remains in the final DML, eliminating the check/write race and one JDBC round trip.

## JDBC round-trip inventory

### Dream semantic prior apply

Approximate calls for a new pair before stabilization:

- authority probe: 1;
- lifecycle row locks: 2;
- node advisory locks: 2;
- pair existence: 1;
- semantic degree counts: 2;
- directional upserts: 2;
- total: approximately 10 JDBC calls.

After stabilization:

- lifecycle row locks: 2;
- node advisory locks: 2;
- combined pair existence + two degree counts: 1;
- bidirectional fenced upsert: 1;
- total: approximately 6 JDBC calls.

The mutation-time fence is retained.

### Ingestion semantic seeding

The same structural reduction was applied to `SemanticAssociationSeedRepository`: existence and both degree counts are read in one statement and both directions are written in one statement. Its ingestion behavior remains distinct from Dream: refresh uses the strongest observed semantic similarity rather than Dream's current revalidation value.

## Transaction-boundary audit

### Explicitly bounded boundaries

The application already provides dedicated bounded transaction templates for retrieval, publication, cleanup/maintenance, repair and re-embedding. Dream semantic prior apply creates a short transaction with the configured Dream transaction timeout.

### Generic primary transaction template

The primary `TransactionTemplate` has no explicit timeout. It is still injected into several repositories, including online graph reinforcement, ingestion semantic seeding, policy/query-memory and lifecycle/idempotency repositories.

Decision for this PR: **do not assign an arbitrary global timeout**. A global value could terminate valid publication, reconciliation or ingestion work whose SLA differs from retrieval and graph mutation. Any remaining generic-template consumer must either be demonstrated bounded by workload/locking design or moved to a domain-specific timeout sourced from validated configuration.

Open P1 before baseline declaration: online graph reinforcement and semantic seeding must have an explicit bounded mutation transaction policy or a documented measured reason why the primary boundary is safe.

## Connection-pool audit

No repository-owned Hikari pool sizing override was found in the main application configuration. That means deployment/runtime defaults and external configuration currently determine pool sizing.

Decision: do not hard-code `maximumPoolSize`, `minimumIdle`, `connectionTimeout` or `maxLifetime` without saturation evidence. Pool changes must be based on the performance harness and database connection budget. The baseline gate is therefore: document measured concurrency and confirm that graph/Dream work cannot multiply DB concurrency beyond configured Dream v1 serial execution.

## SQL and index audit

Confirmed schema facts:

- migration 011 adds `graph_version` to the association primary key, matching current `ON CONFLICT` targets;
- semantic prior columns are introduced by migration 015;
- existing graph provisioning indexes are optimized primarily for online source lookup/maintenance and target lookup;
- Dream/semantic admission repeatedly counts active semantic edges by `(access_level, source_document_id, source_generation, source_chunk_id, graph_version)` with `semantic_similarity IS NOT NULL AND band <> 'DECAYED'`.

Action: migration 027 adds a partial `idx_kca_active_semantic_degree` index matching that admission predicate.

## Dead code / compatibility cleanup

Completed in this stream:

- removed duplicate graph lifecycle/advisory-lock helper implementations from graph mutation repositories;
- removed the redundant Dream preliminary authority probe;
- removed local JDBC timeout conversion logic;
- removed an obsolete Spring Boot management-security autoconfiguration reference from the request-body transport integration test that no longer exists in the current dependency set.

Further deletion is allowed only when references and behavior are proven dead; compatibility code is not removed solely for cosmetic simplification.

## Integration and failure-injection coverage

Existing graph integration tests already exercise PostgreSQL/Liquibase, ACL isolation, graph-version isolation, evidence idempotency and partition pruning.

Added Dream writer integration coverage on real PostgreSQL for:

- valid fenced authority writes both directions;
- semantic writes leave learned evidence counters at zero;
- stale fencing token rejects mutation and rolls back the pair completely;
- expired lifecycle rejects mutation before graph state changes.

Added unit coverage for shared JDBC timeout rounding and invalid durations.

Remaining desired failure coverage before baseline declaration:

- concurrent reversed pair mutations prove canonical ordering avoids deadlock;
- degree-limit admission at either endpoint;
- transaction timeout/rollback injection on graph mutation;
- lease expiry during a bounded Dream write where mutation-time fencing is authoritative.

## Performance / benchmark gate

The existing Retrieval Storage Final Benchmark is part of the required gate set. Structural JDBC-call reduction is not treated as a latency claim by itself. Any performance claim in the final baseline record must come from workflow output or a reproducible profiler/benchmark run on the final SHA.

## Exit criteria

The baseline can be declared **QUALITY-STABLE** only when all of the following are true on one final SHA:

- CI passes;
- Retrieval Quality Gate passes;
- Retrieval Storage Final Benchmark passes;
- Production Image Build passes;
- no known P0/P1 correctness defect remains in the stabilization scope;
- graph/Dream transaction boundaries are bounded and documented;
- SQL/index audit has no known hot-path full-scan defect;
- integration/failure-injection coverage above is complete or any residual item is explicitly downgraded with evidence;
- benchmark evidence and final workflow run IDs are recorded here;
- PR #65 is no longer draft only after these conditions are met.

## Final baseline record

Not yet assigned.

- Final SHA: pending
- CI: pending
- Retrieval Quality Gate: pending
- Retrieval Storage Final Benchmark: pending
- Production Image Build: pending
- Benchmark observations: pending
