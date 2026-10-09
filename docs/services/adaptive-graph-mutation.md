# Adaptive graph mutation contract

Status: **DRAFT** until one exact PR-head SHA passes CI, Retrieval Quality Gate, Retrieval Storage Final Benchmark, and Production Image Build.

## Purpose

Adaptive graph mutation maintains bilateral chunk associations without allowing stale, expired, unpublished, cross-ACL, partially committed, or non-authoritative graph state to become durable.

The shared mutation authority is `GraphMutationLocks`. It is used by:

- `AdaptiveChunkGraphRepository` for learned online evidence;
- `SemanticAssociationSeedRepository` for semantic seeding;
- `SemanticGraphPriorWriter` for fenced Dream semantic priors.

The three writers intentionally have different evidence/admission semantics, but they must share the same lifecycle and lock-order authority.

## Entry points

- `AdaptiveChunkGraphRepository.reinforceSymmetric(...)` / `reinforceSymmetricBatch(...)`;
- `SemanticAssociationSeedRepository.seedSymmetric(...)`;
- `SemanticGraphPriorWriter.applyCandidate(...)`;
- `GraphMutationLocks.lockEligiblePublishedNodes(...)` is the common transaction-scoped authority primitive.

## Shared authority sequence

Every graph mutation transaction follows this ordering:

```text
validate input pair(s)
  -> canonicalize all nodes with ChunkGraphNode.compareTo
  -> begin bounded database transaction
  -> require actual transaction context
  -> lifecycle FOR SHARE in canonical node order
       READY
       ACTIVE
       exact access_level
       exact published_generation
       non-expired by PostgreSQL clock_timestamp()
  -> exact generation FOR SHARE in canonical node order
       exact document/generation/access_level
       generation_status = PUBLISHED
  -> pg_advisory_xact_lock(nodeKey) in canonical node order
  -> writer-specific state/admission check
  -> bilateral mutation in one SQL statement
  -> COMMIT
```

No writer may duplicate or weaken the shared lifecycle/advisory locking SQL.

## Business rules

### GRAPH-BR-01 — mutation requires a real database transaction

`GraphMutationLocks` must fail before taking authority locks when Spring does not report an active database transaction.

Reason: PostgreSQL transaction-scoped advisory and row locks executed in autocommit would be released immediately and would create only an illusion of mutation ownership.

Negative case:

```text
caller -> GraphMutationLocks directly outside TransactionTemplate
       -> IllegalStateException
       -> no authority claim
```

### GRAPH-BR-02 — canonical ordering is global

`ChunkGraphNode.compareTo` defines lock order by:

1. access level;
2. document id;
3. generation;
4. chunk id.

`GraphMutationLocks` canonicalizes the complete node set with a `TreeSet` before any row/advisory lock is taken.

Reversed calls `(A,B)` and `(B,A)` must therefore acquire the same lock sequence.

### GRAPH-BR-03 — lifecycle row is the first publication fence

A node is eligible only when `knowledge_document_lifecycle` says:

- exact `document_id`;
- exact `access_level`;
- `published_generation = node.generation`;
- `lifecycle_status = READY`;
- `retention_status = ACTIVE`;
- `expires_at IS NULL OR expires_at > clock_timestamp()`.

The lifecycle row is locked `FOR SHARE` before generation or advisory locks.

This lock order remains compatible with publication, retention and reconciliation paths that acquire lifecycle authority before generation mutation.

### GRAPH-BR-04 — lifecycle pointer alone is not enough

After lifecycle rows are locked, the exact `knowledge_document_generation` row must also exist with:

```text
generation_status = PUBLISHED
access_level = node.accessLevel
```

and is locked `FOR SHARE` in canonical order.

A corrupt state where lifecycle still points at generation `N` but generation `N` has already become `RETIRED`, `FAILED`, `CLEANED`, or another non-PUBLISHED state must fail closed.

### GRAPH-BR-05 — advisory locks serialize graph node admission/mutation

After lifecycle and generation fences, each canonical node receives:

```sql
pg_advisory_xact_lock(hashtextextended('akmai:adaptive-graph:node:' || lockKey, 0))
```

The advisory lock is transaction-scoped. Hash collision may reduce concurrency but cannot widen mutation authority.

### GRAPH-BR-06 — bilateral writes are atomic

Every logical pair is stored in both directions in one PostgreSQL statement:

```text
A -> B
B -> A
```

If either direction violates a constraint or the statement fails, neither direction may remain committed.

For an online batch, all pair writes share the outer graph mutation transaction. A later pair failure must roll back earlier pair writes from the same batch.

### GRAPH-BR-07 — online learned evidence is idempotent by query bucket

`AdaptiveChunkGraphRepository` may reinforce CANDIDATE/WARM/HOT associations only.

Learned evidence counters are incremented only when `query_support_sketch` gains a new bucket. Replaying the same query bucket must not inflate support/context/citation counts.

Online learned evidence remains distinct from Dream semantic prior evidence.

### GRAPH-BR-08 — semantic seed degree admission is bilateral and serialized

`SemanticAssociationSeedRepository` reads pair existence plus semantic degree for both nodes only after shared node locks are held.

For a new pair:

```text
firstDegree >= maxSemanticDegree
OR secondDegree >= maxSemanticDegree
  -> reject without write
```

An existing pair may refresh semantic evidence without consuming a new degree slot.

Concurrent reversed seeding must serialize through the same canonical node locks, so both calls observe a stable admission state.

### GRAPH-BR-09 — semantic seed refresh keeps strongest observed similarity

Ingestion semantic seeding uses `greatest(existing, incoming)` for `semantic_similarity` and advances `semantic_last_seen_at`.

This differs intentionally from Dream revalidation, which records the current semantic prior value under Dream fencing authority.

### GRAPH-BR-10 — Dream apply requires fenced authority in final DML

`SemanticGraphPriorWriter` additionally requires a live row in `adaptive_graph_dream_lease` matching:

- graph version;
- semantic policy fingerprint;
- owner id;
- fencing token;
- `lease_until > clock_timestamp()`.

The predicate is part of the bilateral mutation DML itself. A stale token therefore changes zero rows and raises `LostDreamAuthorityException`; no preliminary check is considered sufficient authority.

### GRAPH-BR-11 — lock wait is timeout-bounded

Online reinforcement and semantic seeding run through `graphMutationTransactionTemplate`.

Dream apply creates a bounded transaction using the same configured Dream transaction-timeout contract.

A lifecycle/generation/advisory lock wait must therefore terminate through the actual JDBC/transaction boundary rather than relying on polling between operations.

After timeout/rollback, no new bilateral association rows may remain from the failed mutation.

### GRAPH-BR-12 — cross-ACL and self edges are forbidden

All public writers reject:

- null nodes;
- self-association;
- cross-ACL pairs.

Database constraints remain the second line of defense for direct SQL corruption.

## Writer-specific semantics

### Online reinforcement

```text
reinforceSymmetricBatch
  -> validate all observations
  -> canonical lock union of every node in the batch
  -> for each observation
       bilateral INSERT ... ON CONFLICT UPDATE
  -> one transaction commits the entire batch
```

A failure in observation `N` rolls back successful observations `1..N-1` from that batch.

### Semantic seeding

```text
seedSymmetric
  -> canonical pair
  -> shared lifecycle/generation/advisory locks
  -> pair existence + both semantic degrees
  -> reject if new pair exceeds either degree cap
  -> bilateral seed/refresh statement
```

### Dream prior application

```text
applyCandidate
  -> static/runtime apply gate
  -> bounded transaction
  -> shared graph authority locks
  -> pair/degree state
  -> bilateral INSERT/UPDATE guarded by live lease + fencing token
```

Dream does not increment learned support/context/citation counters.

## Positive cases

- two READY/ACTIVE/non-expired PUBLISHED nodes in one ACL may be reinforced;
- repeated online query bucket is idempotent;
- distinct query buckets increase learned evidence;
- a semantic pair below both degree caps is seeded bilaterally;
- an existing semantic pair may be refreshed at the degree cap;
- valid Dream authority applies or refreshes exactly two directions;
- reversed pair calls serialize without deadlock.

## Negative / failure cases

- call shared locks outside a transaction -> reject;
- lifecycle not READY/ACTIVE/current -> reject;
- TTL expired -> reject;
- lifecycle points at a non-PUBLISHED generation -> reject;
- exact generation row missing -> reject;
- cross-ACL pair -> reject;
- self edge -> reject;
- semantic degree exceeded for either endpoint -> no write;
- stale Dream fencing token -> whole pair rolled back/rejected;
- second-direction SQL constraint failure -> zero durable pair rows;
- later online batch pair fails -> earlier pair writes in that batch roll back;
- lifecycle lock blocked beyond transaction timeout -> rollback and zero graph rows.

## Transaction / rollback semantics

All graph mutation writes are database-only and remain inside bounded transactions.

`GraphMutationLocks` relies on transaction scope for both PostgreSQL row locks and `pg_advisory_xact_lock`; this is why an active transaction is now an explicit precondition rather than documentation only.

A transaction failure releases row/advisory locks automatically. No local in-memory ownership survives rollback.

## Concurrency / lock ordering

Global order is:

```text
canonical lifecycle rows
  -> canonical exact generation rows
  -> canonical advisory node locks
  -> writer-specific reads/writes
```

Writers must not acquire graph node advisory locks before lifecycle authority, and must not introduce a reverse generation-before-lifecycle path.

This contract is designed to remain compatible with publication/reconciliation document lock order and to prevent reversed `(A,B)` / `(B,A)` graph deadlocks.

## Timeout / cancellation

- online reinforcement and semantic seeding: `graphMutationTransactionTemplate` timeout;
- Dream prior: explicit `TransactionTemplate` timeout derived through `JdbcTimeouts`;
- a timeout is a failed mutation, not a partial success;
- callers may retry only through their existing business retry/orchestration semantics.

## Observability

At minimum, failures must remain distinguishable by exception/log context as:

- lifecycle/generation ineligible;
- graph transaction timeout / DB failure;
- semantic degree rejection (normal admission result, not infrastructure failure);
- Dream lost fencing authority;
- input-policy rejection such as cross-ACL/self edge.

Do not add document/chunk identifiers as unbounded metric labels.

## Recovery

Graph mutation is retry-safe because:

- row/advisory locks are transaction-scoped;
- bilateral statements are atomic;
- online evidence deduplicates by query bucket;
- semantic refresh is an upsert;
- Dream mutation is guarded by current fencing authority.

After transaction rollback there is no separate graph recovery protocol: the caller may retry under fresh lifecycle/lease authority.

## Tests

Primary executable coverage:

- `AdaptiveChunkGraphRepositoryTest` — normal symmetric association, ACL isolation, stale generation fence, query-bucket idempotency, graph-version isolation;
- `AdaptiveGraphMutationFailureMatrixIntegrationTest` — active transaction precondition, generation-status fence, reversed online/semantic concurrency, forced half-pair rollback, batch rollback and real lifecycle-lock timeout;
- `SemanticGraphPriorWriterIntegrationTest` — valid bilateral Dream apply, stale fencing rollback, expiry rejection, reversed-pair concurrency and real transaction timeout;
- `SemanticGraphPriorWriterTest` — apply gate and graph-version rejection.

## Definition of Done

- [x] Canonical node ordering documented and exercised concurrently
- [x] Lifecycle authority locked before graph mutation
- [x] Exact generation must still be PUBLISHED
- [x] Shared primitive rejects non-transactional use
- [x] Bilateral half-pair rollback tested
- [x] Online batch rollback tested
- [x] Reversed online pair concurrency tested
- [x] Reversed semantic pair concurrency tested
- [x] Dream stale-fence rollback retained
- [x] Real lock timeout coverage exists for online/semantic and Dream
- [x] Cross-ACL/self-edge policy retained
- [ ] Final exact PR-head CI/quality/storage/image checks green
- [ ] Inventory promoted from DRAFT only after final verification
