# Critical process proof review — 2026-10-12

Classification: **CURRENT COVERAGE REVIEW / NO TEST-COUNT TARGET**

Branch: `quality/production-readiness-hardening-v1`

## Review rule

This review does not require a test merely because a service is important. A new test is justified only when a material business invariant lacks executable proof in one of four dimensions:

1. positive behavior;
2. negative/fail-closed behavior;
3. concurrency/ownership behavior where distributed state exists;
4. failure/recovery/rollback behavior where partial execution is possible.

Unit evidence is sufficient for deterministic local orchestration. PostgreSQL integration evidence is required for claims about leases, fencing, uniqueness, transactional rollback, row/advisory locks, lifecycle time or multi-replica ownership.

## Executive conclusion

The critical runtime chain is now substantially covered. This pass found two material proof gaps and closed both:

- concurrent same-key ingestion idempotency ownership;
- re-embedding scheduler heartbeat lease renewal/recovery semantics.

The earlier part of this branch also closed direct Query Memory lifecycle/concurrency and learning-recorder failure/privacy evidence.

No additional material test gap was found in retrieval, retention, publication, graph/Dream, reconciliation, scheduled jobs or runtime flags that would justify adding tests solely for count.

## 1. Retrieval — ENOUGH

### Positive proof

- baseline semantic and identifier routing;
- successful lane execution and typed outcomes;
- multi-lane fusion and reranking;
- graph expansion when eligible;
- bounded context selection and one-round backfill;
- grounded top-level retrieval flow.

Representative evidence:

- `RetrievalPlannerTest`;
- `RetrievalPlannerRoutingTest`;
- `ParallelRetrievalExecutorDegradationMatrixTest`;
- `ResultFusionTest`;
- `RerankerTest`;
- `ContextSelectionCoordinatorTest`;
- `RagQuestionServiceRetrievalFlowTest`.

### Negative / fail-closed proof

- missing ACL scope;
- unsafe adaptive/canary lane pruning;
- identifier miss without global semantic fallback;
- stale/unpublished lifecycle filtering;
- malformed/expired temporal parent evidence;
- citation/grounding rejection;
- context/token budget rejection.

### Concurrency / race proof

Retrieval is read-oriented rather than one distributed ownership state machine. The important race is lifecycle/publication TOCTOU. It is directly covered by candidate-time canonical eligibility plus final `PublishedContextRevalidator`, including final-context expiry/publication races.

### Failure / recovery proof

- typed strategy failure/degradation/criticality matrix;
- request and strategy cancellation;
- reranker fallback;
- graph-expansion fail-safe behavior;
- real PostgreSQL retrieval transaction timeout with repeated connection reuse.

**Verdict:** no new retrieval test required.

## 2. Ingestion — GAP CLOSED

### Positive proof

- text and canonical ingestion;
- replay of successful idempotent request;
- generation allocation, embedding and publication;
- ambiguous committed publication recovery.

### Negative proof

- invalid language/input;
- zero searchable chunks;
- fingerprint conflict;
- inactive embedding profile;
- embedding failure/cardinality mismatch;
- lost idempotency ownership;
- publication `NOT_COMMITTED` / unknown outcome;
- cleanup-failure suppression.

### Concurrency proof

Existing orchestration tests did not directly prove simultaneous same-key claim ownership at PostgreSQL level. This branch closes that gap in `IdempotencyIntegrationTest`:

```text
two concurrent claim(key, fingerprint)
  -> exactly one CLAIMED
  -> exactly one IN_PROGRESS
  -> exactly one durable request row
```

The database row/lease boundary is therefore the ownership authority rather than JVM coordination.

### Failure / recovery proof

- expired claim recovery;
- published-manifest replay recovery;
- primary failure preserved over cleanup failure;
- publication ambiguity resolved from durable state.

**Verdict:** concurrency proof gap closed; no further ingestion test required in this pass.

## 3. Retention — ENOUGH

### Positive proof

- eligible TTL candidate claim;
- bounded worker processing;
- successful generation cleanup;
- backlog accounting.

### Negative proof

- staging replacement blocks destructive retention;
- destructive `DELETING` state blocks replacement allocation;
- stale/invalid claim is rejected;
- current/published generation safety fences remain authoritative.

### Concurrency proof

`RetentionClaimRepositoryMultipodIntegrationTest` proves `FOR UPDATE SKIP LOCKED`, lease takeover and multi-worker claim separation. `RetentionReingestionRaceTest` proves retention/re-ingestion generation races.

### Failure / recovery proof

`ChunkRetentionServiceIntegrationTest` proves transactional rollback on late destructive failure. `RetentionLeaseExpiryIntegrationTest` proves lease expiry after destructive work begins rolls the transaction back to the pre-cleanup durable state.

**Verdict:** no new retention test required.

## 4. Publication — ENOUGH

### Positive proof

- first publication;
- replacement publication;
- ACL cutover only at commit;
- already-published handling;
- committed ambiguous-outcome recovery.

### Negative proof

- active embedding profile mismatch;
- stale/superseded candidate;
- storage/persistence failure;
- lifecycle/candidate retirement fence failure;
- idempotency completion failure;
- ambiguous outcome without durable evidence.

### Concurrency proof

`ConcurrentGenerationPublicationIntegrationTest` and `GenerationPublicationLifecycleIntegrationTest` prove concurrent N/N+1 publication converges on the newer generation and cannot move visibility backward.

### Failure / rollback proof

Late vector-stage failure rolls back earlier projection/manifest/state mutations. Resolver failure preserves the original publication exception and adds recovery failure as suppressed. Post-commit semantic linking failure does not roll back committed publication.

**Verdict:** no new publication test required.

## 5. Adaptive graph / Dream — ENOUGH

### Positive proof

- symmetric graph mutation;
- lifecycle-filtered graph lookup;
- valid Dream candidate observation/activation;
- valid semantic prior apply;
- lease owner execution.

### Negative proof

- expired/non-published endpoints cannot be used;
- graph lookup filters invalid endpoints before `LIMIT`;
- Dream endpoint invalidation is distinguished from lease loss;
- stale fencing token cannot apply/retire authoritative graph state.

### Concurrency proof

`AdaptiveGraphMutationFailureMatrixIntegrationTest` covers reversed-pair concurrency, concurrent generation invalidation while mutation waits and canonical lock ordering. Dream lease/checkpoint and semantic-prior tests cover owner/fence races.

### Failure / rollback proof

- forced half-pair graph failure rolls back symmetry;
- batch failure rolls back;
- lifecycle-lock/transaction timeout is bounded;
- endpoint invalidation skips only the affected Dream candidate;
- actual lease loss remains a hard authority failure.

**Verdict:** no new graph/Dream test required.

## 6. Generation reconciliation — ENOUGH

### Positive proof

- stale/incomplete generations become repair candidates;
- bounded residual cleanup may continue on a later run;
- stale `PURGING` work can be reclaimed.

### Negative proof

- currently published generation is never selected for destructive repair;
- fresh busy ownership is not stolen;
- lifecycle/generation state is revalidated before destructive work.

### Concurrency proof

`GenerationReconciliationMultipodIntegrationTest` and candidate advisory-lock behavior prove replicas do not destructively repair the same candidate concurrently.

### Failure / recovery proof

`GenerationReconciliationFailureMatrixIntegrationTest` covers bounded residual deferral and subsequent completion, plus publication-preservation fences.

**Verdict:** no new reconciliation test required.

## 7. Scheduled/background jobs — ENOUGH

The scheduler trigger itself is not treated as authority. Each mutating job is classified by its actual PostgreSQL ownership primitive:

- Dream — singleton lease + fencing;
- retention — claimed/partitioned work;
- reconciliation — candidate advisory ownership;
- re-embedding heartbeat — owner-scoped lease renewal;
- graph maintenance — `SKIP LOCKED` partitioning plus transactional mutation;
- audit partition maintenance — transaction advisory lock;
- retention economics sampler — local telemetry with no domain mutation.

### Newly closed proof

`ReembeddingLeaseHeartbeatSchedulerIntegrationTest` now directly proves scheduler-level behavior rather than only manual lease renewal:

- heartbeat extends the live owner's PostgreSQL lease;
- contender cannot take over after renewal;
- an expired lease is not resurrected by the stale scheduler;
- another replica may claim the expired migration with a higher fencing token.

**Verdict:** scheduled-job ownership matrix is adequately evidenced; no additional scheduler test required.

## 8. Runtime safety flags — ENOUGH

### Positive proof

`AppParameterServiceTest` covers cache reads, authoritative refresh and successful administrative transition behavior.

### Negative proof

It also covers corrupt values, authoritative database outage, optimistic conflict and stale local cache eviction.

### Concurrency proof

`PostgresAppParameterRepositoryIntegrationTest.concurrentDependencyUpdatesCannotCommitInvalidGraphState` executes conflicting administrative transitions concurrently and proves registry locking prevents an invalid combined state.

### Failure / recovery proof

- cached/fail-safe consumers may use last-known-good/static fallback;
- mutation-capable authoritative consumers fail closed on PostgreSQL unavailability;
- older completion cannot overwrite a newer local version;
- optimistic conflict forces subsequent state reacquisition.

**Verdict:** gap was documentation-to-test mapping, already corrected; no new runtime-flag test required.

## Cross-cutting conclusion

For the requested chain:

```text
retrieval
  -> ingestion
    -> retention
      -> publication
        -> graph/Dream
          -> reconciliation
            -> scheduled jobs/runtime flags
```

all material positive, negative, concurrency and failure/recovery boundaries now have executable evidence at the appropriate layer.

This does **not** mean every class needs four separate tests. It means every material invariant has proof somewhere at the correct authority boundary.

Further test additions in these areas should require one of:

- a newly discovered defect;
- a new business invariant;
- a changed transaction/lock/lease boundary;
- a production incident;
- a benchmark or chaos result showing an uncovered failure mode.

Absent one of those triggers, adding more tests here would mostly increase maintenance cost rather than production confidence.

## Remaining work is release qualification, not core proof coverage

The remaining blockers are external to this critical-process test matrix:

1. exact-head CI/quality/storage/image gates;
2. approved immutable RAG baseline;
3. integrated live release qualification;
4. target-hardware SLO/capacity evidence;
5. default-branch governance;
6. human-reviewed external-validity corpus for broad accuracy claims.
