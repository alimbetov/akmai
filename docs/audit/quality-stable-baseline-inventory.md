# Quality Stable Baseline — Code Duplication Inventory

Scope: post-DREAM-5 stabilization only. No new retrieval, learning, Dream, or ranking features are added in this phase.

## Priority duplication inventory

| Area | Current duplication | Risk | Target primitive | Action |
| --- | --- | --- | --- | --- |
| Graph node locking | `pg_advisory_xact_lock(hashtextextended(...))` repeated in `AdaptiveChunkGraphRepository`, `SemanticAssociationSeedRepository`, and `SemanticGraphPriorWriter` | Lock namespace/order can drift and create deadlocks | `GraphNodeLockManager` | Centralize canonical node lock acquisition |
| Graph lifecycle eligibility | Published generation checks repeated across online reinforcement, semantic seeding, and Dream apply with different predicates | Writers can disagree on READY/ACTIVE/TTL eligibility | `GraphLifecycleGuard` | Centralize strict semantic eligibility; keep explicitly documented learned-write mode where semantics differ |
| Transaction timeout conversion | Duration-to-seconds clamping implemented locally in Dream writer/query paths | Timeout drift; overflow/zero edge cases | `TransactionTimeouts` | One bounded conversion primitive |
| Dream fencing predicate | graphVersion/policyFingerprint/ownerId/fencingToken/leaseUntil predicate repeated in lease manager, candidate/checkpoint/run repositories and Dream graph writer | Stale-owner rejection can drift | `DreamAuthorityGuard` | Centralize transactional ownership validation |
| Canonical graph pair ordering | Repeated `compareTo`/`TreeSet` ordering across graph writers | Direction/order inconsistencies | `GraphPairCanonicalizer` | One pair-order primitive |
| Semantic graph state probe | Pair existence and semantic degree queries duplicated in semantic seed and Dream writer | Duplicated SQL and extra JDBC round-trips | `SemanticGraphStateRepository` | Consolidate pair existence + both endpoint degrees in one query |
| Symmetric graph writes | Directional upsert logic exists independently for learned, semantic seed, and Dream prior paths | Atomicity and field-preservation rules can diverge | Shared identity/binding primitives only | Do not unify mutation SQL until semantics are proven equivalent |
| Lease ownership patterns | Dream and re-embedding both use owner/token/DB-time lease patterns | Similar infrastructure but different identity/status semantics | Small fencing/timeout primitives | Avoid a generic lease framework until contracts converge |

## Explicit non-goals

- No `CommonUtils`, `DbUtils`, or generic helper dumping ground.
- No change to graph ranking, learning thresholds, Dream stages, or online retrieval semantics.
- No unification of learned-evidence and semantic-prior upsert SQL: their state transition rules differ.
- No weakening of fencing, lifecycle, ACL, generation, or TTL invariants for performance.

## Execution order

1. Centralize graph locking, lifecycle validation, timeout conversion, fencing validation, and pair ordering.
2. Consolidate read probes inside graph mutation transactions to reduce JDBC round-trips.
3. Audit transaction boundaries and remove network/long-running work from DB transactions.
4. Run benchmark/profiling passes before and after query consolidation.
5. Audit Hikari pool pressure, SQL plans, indexes, and lock order.
6. Remove dead/compatibility code only after call-site search and test proof.
7. Add integration/failure-injection coverage for locks, fencing, timeout, rollback, pool pressure, and stale ownership.
8. Declare quality-stable only after all CI/quality/benchmark gates are green on one final SHA.
