# Quality-stable baseline v1

Status: **CANDIDATE — final same-SHA gates required**

Branch: `quality/quality-stable-baseline-v1`  
PR: #65

This is the stabilization ledger after `feature/adaptive-graph-dream` was merged. The baseline is declared only when one final PR-head SHA passes all required workflows.

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

A dedicated `graphMutationTransactionTemplate` now bounds online graph reinforcement and ingestion semantic seeding using the existing validated adaptive-graph transaction timeout. The generic Primary template is no longer used by these graph mutation hot paths.

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

The Primary `TransactionTemplate` remains unbounded for several short repository coordination paths. No global timeout was imposed because those domains have different lease/publication/idempotency semantics. This is classified as P2 operability debt rather than a graph correctness blocker; future work should move each remaining generic consumer to a domain-specific timeout as its SLA is defined.

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

Added real PostgreSQL/Testcontainers coverage for Dream semantic prior apply:

- valid fenced authority writes both directions;
- learned evidence counters stay zero;
- stale fencing token rejects the mutation and leaves no half-pair;
- expired lifecycle rejects mutation before graph state changes.

Added unit coverage for shared JDBC timeout rounding and invalid durations.

Additional reversed-pair deadlock stress and forced transaction-timeout injection are classified as P2 hardening because canonical lock ordering, atomic pair DML, bounded graph transactions and stale-fence rollback are already directly covered by implementation/integration contracts. They remain worthwhile follow-up stress tests, but are not P0/P1 baseline blockers.

## Performance / benchmark assessment

The existing Retrieval Storage Final Benchmark remains a required release gate. For graph-only changes its heavy retrieval matrices may be classified as not retrieval-sensitive and skipped, so a green workflow is not presented as graph latency evidence.

Performance claims in this stabilization are therefore limited to reproducible structural facts:

- Dream apply reduces its JDBC call budget from ~10 to ~6 for a new pair;
- semantic ingestion seeding applies the same consolidation;
- semantic-degree admission has a matching partial index;
- Dream v1 remains single-concurrency for DB/ANN work.

No unmeasured p50/p95/p99 improvement is claimed.

## Exit criteria

The baseline can be declared **QUALITY-STABLE** when one final PR-head SHA has:

- CI = success;
- Retrieval Quality Gate = success;
- Retrieval Storage Final Benchmark = success;
- Production Image Build = success;
- no known P0/P1 correctness defect in this stabilization scope;
- bounded graph/Dream transaction boundaries;
- no known semantic-degree hot-path index gap;
- the integration/failure cases above green.

The exact final SHA and workflow run IDs are recorded in the PR/release decision rather than embedded here, because embedding a commit's own SHA or its post-commit workflow IDs would itself create a new commit and invalidate the same-SHA condition.
