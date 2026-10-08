# Quality-stable baseline v1

Status: **CANDIDATE — completion SHA gates required**

Branch: `quality/quality-stable-completion`  
Prior baseline PR: #65  
Final-gate fix PR: #68

This is the stabilization ledger after `feature/adaptive-graph-dream` was merged. The baseline is declared only when one final completion PR-head SHA passes all required workflows.

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

- graph nodes are locked in deterministic canonical order;
- lifecycle eligibility is `READY + ACTIVE + published generation + non-expired`;
- ACL identity is preserved and cross-ACL graph pairs are rejected;
- Dream fencing is checked in the actual mutation statement inside the transaction;
- a stale Dream owner cannot materialize either direction of a semantic pair;
- semantic prior writes never synthesize learned support/context/citation/query evidence;
- bidirectional graph changes are atomic at transaction scope;
- DB/ANN failures are not semantic negative evidence;
- no Dream run-wide transaction is allowed.

## Duplication inventory and extracted primitives

### Graph mutation locking

`AdaptiveChunkGraphRepository`, `SemanticAssociationSeedRepository` and `SemanticGraphPriorWriter` previously repeated lifecycle row locking, canonical node ordering and advisory locking.

Action: extracted `GraphMutationLocks` and migrated all three mutation paths to it.

### Lifecycle predicate

Mutation-side lifecycle validation is centralized. Read-side lifecycle SQL remains explicit because aliases, joins, partition pruning and plan shape differ; a shared SQL-string fragment would reduce readability without reducing database work.

### Timeouts

Action: extracted `JdbcTimeouts` for positive-duration validation and ceil-to-seconds JDBC semantics. Semantic ANN and Dream transaction timeout conversion reuse the same rule.

A dedicated `graphMutationTransactionTemplate` bounds online graph reinforcement and ingestion semantic seeding using the validated adaptive-graph transaction timeout. Dream apply creates a short bounded transaction from the same timeout contract.

### Dream fencing

The redundant preliminary authority SELECT was removed. The authoritative lease/fencing predicate remains in the final mutation DML, eliminating a check/write race and one round trip.

## JDBC round-trip audit

### Dream semantic prior apply

New-pair path changed from approximately 10 JDBC calls to approximately 6:

- lifecycle locks: 2;
- advisory locks: 2;
- combined existence + two degree counts: 1;
- bidirectional fenced upsert: 1.

Previously, authority probing, pair existence, two degree queries and two directional upserts were separate calls.

### Ingestion semantic seeding

The same pair-state consolidation and bidirectional single-statement write were applied to `SemanticAssociationSeedRepository`. Ingestion semantics remain intentionally distinct: refresh keeps the strongest semantic similarity, while Dream records its current revalidation value.

## Transaction-boundary audit

Dedicated bounded templates exist for retrieval, publication, graph mutation, cleanup/maintenance, repair and re-embedding. Dream apply creates its own short bounded transaction. ANN work remains outside graph mutation transactions.

The Primary `TransactionTemplate` remains unbounded for several short repository coordination paths. No global timeout is imposed because those domains have different lease/publication/idempotency semantics. This remains P2 operability debt and is not a graph correctness blocker; each remaining generic consumer should move to a domain-specific timeout only when its SLA is explicitly defined.

The completion hardening adds a real PostgreSQL failure-injection test that blocks the lifecycle row from a separate connection and verifies the Dream transaction timeout terminates the JDBC wait without leaving graph state behind.

## Connection-pool audit

No repository-owned Hikari sizing override was found in application configuration. Deployment/runtime configuration therefore owns pool sizing.

No arbitrary `maximumPoolSize`, `minimumIdle`, `connectionTimeout` or `maxLifetime` values are introduced by this stabilization. Dream v1 remains serial for DB/forward-ANN/reverse-ANN concurrency, preventing Dream from multiplying pool demand. Pool tuning must follow measured saturation and the deployment database connection budget.

## SQL and index audit

- migration 011 adds `graph_version` to the association PK, matching current `ON CONFLICT` targets;
- migration 015 introduces semantic-prior columns;
- existing graph provisioning indexes target online source lookup/maintenance and target lookup;
- semantic admission repeatedly counts active semantic edges by source identity + graph version with `semantic_similarity IS NOT NULL AND band <> 'DECAYED'`.

Action: migration 027 adds partial index `idx_kca_active_semantic_degree` matching that admission predicate.

## Dead code / compatibility cleanup

Completed:

- duplicate graph lifecycle/advisory-lock helpers removed from mutation repositories;
- redundant Dream authority preflight removed;
- duplicate JDBC timeout conversion removed;
- obsolete Spring Boot management-security autoconfiguration reference removed from the request-body transport integration test;
- no additional referenced `@Deprecated`, legacy or compatibility graph/Dream scaffolding was found that could be safely deleted solely by static inventory.

## Integration and failure-injection coverage

Existing PostgreSQL graph tests cover ACL isolation, graph-version isolation, evidence idempotency and partition pruning.

Real PostgreSQL/Testcontainers Dream semantic-prior coverage now includes:

- valid fenced authority writes both directions;
- learned evidence counters stay zero;
- stale fencing token rejects the mutation and leaves no half-pair;
- expired lifecycle rejects mutation before graph state changes;
- reversed-pair concurrent apply stress completes without deadlock and preserves exactly two directional rows;
- a forced lifecycle-row lock wait is interrupted by the configured transaction timeout and leaves no graph mutation.

Shared JDBC timeout rounding and invalid-duration behavior also have direct unit coverage.

## Performance / benchmark assessment

The Retrieval Storage Final Benchmark remains a required release gate. For graph-only changes its heavy retrieval matrices may be classified as not retrieval-sensitive and skipped, so a green workflow is not presented as graph latency evidence by itself.

Performance claims in this stabilization are intentionally limited to reproducible structural facts:

- Dream apply reduces its JDBC call budget from ~10 to ~6 for a new pair;
- semantic ingestion seeding applies the same consolidation;
- semantic-degree admission has a matching partial index;
- Dream v1 remains single-concurrency for DB/ANN work.

No unmeasured p50/p95/p99 improvement is claimed.

## Prior final-gate evidence

PR #68 head SHA `3151451f12537566653ba9e5c2d415c2e74c9b66` passed all four required workflows before merge:

- CI — success;
- Retrieval Quality Gate — success;
- Retrieval Storage Final Benchmark — success;
- Production Image Build — success.

Because completion hardening adds new integration tests, the final `QUALITY-STABLE` declaration requires the same four gates on one exact completion PR-head SHA.

## Exit criteria

The baseline is declared **QUALITY-STABLE** when one final completion PR-head SHA has:

- CI = success;
- Retrieval Quality Gate = success;
- Retrieval Storage Final Benchmark = success;
- Production Image Build = success;
- no known P0/P1 correctness defect in this stabilization scope;
- bounded graph/Dream transaction boundaries;
- no known semantic-degree hot-path index gap;
- all integration/failure-injection cases above green.

The exact final SHA and workflow run IDs are recorded in the PR/release decision rather than embedded into this file after the final gate run, because a post-gate documentation commit would create a new SHA and invalidate the same-SHA condition.
