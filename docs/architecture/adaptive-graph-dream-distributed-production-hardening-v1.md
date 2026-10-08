# Adaptive Graph Dream — distributed-production hardening v1

Classification: **NORMATIVE / RELEASE-BLOCKING FOR DREAM-1..DREAM-4B**  
Target branch: `feature/adaptive-graph-dream`  
Baseline: `main@381b9c6cbf835daf2b301280171c049596e430a3`  
Deployment profile: **4–6 AkmAI pods, one active Dream owner in v1**  
Date: 2026-10-08

This document extends `adaptive-graph-dream-implementation-spec-v1.md` and `adaptive-graph-dream-dream1-4b-engineering-spec.md` with distributed-production failure semantics. Where this document is stricter, this document wins for DREAM-1 through DREAM-4B.

The purpose is to eliminate classes of defects that typically appear only under replica races, rolling deployment, database failover, scheduler overlap, resource saturation, clock disagreement, replay and partial failure.

---

## 1. Distributed correctness model

Dream v1 uses a **single-writer, multi-standby** model.

```text
4–6 service pods
      |
      +-- all may serve RAG traffic
      +-- all may attempt Dream lease acquisition
      |
      +--> exactly one live Dream Authority
             owner_id
             fencing_token
             lease_until
```

Correctness MUST NOT depend on Kubernetes leader election, pod names, graceful shutdown, local memory or scheduler timing. PostgreSQL is the coordination authority.

A process may believe it is owner while already stale. Therefore every authoritative DB mutation MUST be fenced in the same transaction that performs the mutation.

---

## 2. Time authority and clock-skew rule

### DP-01 — database time is authoritative

Lease validity, TTL eligibility, run timestamps used for coordination, and fencing-sensitive expiration decisions MUST use PostgreSQL time:

```sql
clock_timestamp()
```

Application wall clock (`Instant.now()`, pod/NTP time) MUST NOT decide whether a lease is live or expired.

Application time may be used for non-authoritative local deadlines/metrics only.

Reason: with 4–6 pods, even modest clock skew can otherwise produce premature takeover or stale-owner writes.

### Required test

Simulate owners with deliberately skewed application clocks. Lease acquisition/renewal/takeover result must remain unchanged because DB time decides authority.

---

## 3. Owner identity and pod restart

### DP-02 — pod name alone is not an owner identity

Required owner identity:

```text
<pod-name>/<process-start-uuid>
```

or another value guaranteed unique per process incarnation.

A pod restart with the same Kubernetes pod name MUST produce a new owner ID.

This prevents a restarted process from accidentally matching lease rows created by its previous incarnation.

---

## 4. Lease acquisition and thundering herd

All 4–6 pods may receive the same cron trigger. They will race to acquire one lease.

### DP-03 — lease acquisition must be one atomic SQL operation

The acquisition API MUST NOT perform:

```text
SELECT lease
if expired
UPDATE lease
```

as separate operations.

The implementation must use atomic INSERT/UPDATE semantics with `RETURNING fencing_token`, or a row-locking equivalent.

Conceptual contract:

```java
Optional<DreamAuthority> tryAcquire(
    int graphVersion,
    String policyFingerprint
);
```

Only one contender may receive an authority object for a live epoch.

### DP-04 — contention is normal

Failure to acquire because another pod owns the live lease is not logged as an error. It increments a low-cardinality standby/contended metric and returns immediately.

Do not retry aggressively in a tight loop after losing a cron-time race. This prevents a DB thundering herd.

Recommended behavior:

```text
cron event
  -> one acquisition attempt
  -> if not owner: return
```

Takeover after actual owner failure may be retried with bounded randomized jitter, not sub-second polling from all standby pods.

---

## 5. Fencing token semantics

### DP-05 — fencing token is an epoch, not merely metadata

Each successful takeover to a new owner increments the fencing token.

Every authoritative write must prove:

```text
lease.owner_id = authority.ownerId
AND lease.fencing_token = authority.fencingToken
AND lease.lease_until > clock_timestamp()
```

This predicate must be evaluated in the same database transaction as the mutation.

Applies to:

- semantic-prior apply;
- semantic-prior retirement;
- checkpoint advancement;
- run successful/final authoritative completion where completion implies ownership;
- any future Dream operation that changes online graph state.

Candidate shadow observations may be idempotently persisted after read work, but stale ownership must never advance authoritative progress markers.

---

## 6. Heartbeat isolation and JVM stalls

### DP-06 — heartbeat has independent execution capacity

Heartbeat MUST NOT share a saturated executor with:

- forward ANN;
- reverse ANN;
- ingestion embedding;
- retrieval tasks;
- Dream DB worker pool.

Use a dedicated single-thread scheduled executor or equivalent isolated scheduler.

### DP-07 — heartbeat success must be observed by coordinator

A background heartbeat exception must transition a shared authority state to LOST. It is not enough to log the exception.

Conceptual state:

```java
enum DreamAuthorityState {
    LIVE,
    LOST,
    STOPPING
}
```

Coordinator checks authority state:

- before starting each source batch;
- before every graph mutation;
- before checkpoint advancement;
- before run success finalization.

### DP-08 — long JVM pause safety

If the JVM is paused longer than the lease duration, the process may wake believing work is still in progress. It MUST revalidate DB authority before any fenced mutation/checkpoint operation.

Process-local heartbeat state cannot override DB lease state.

---

## 7. Database outage/failover semantics

### DP-09 — DB uncertainty means no authority

If heartbeat cannot reach PostgreSQL, the coordinator must enter a conservative state:

```text
DB communication failure
  -> authority UNKNOWN/LOST for mutation purposes
  -> stop starting new Dream work
  -> do not advance checkpoint
  -> do not apply semantic priors
```

The system must not assume the lease is still valid because its prior renewal succeeded.

ANN reads already in progress may be cancelled or allowed to finish, but their result cannot become authoritative progress until ownership is re-established through a new lease epoch.

### DP-10 — transaction outcome ambiguity

A client/network error after COMMIT may leave the client uncertain whether the DB committed.

Therefore all Dream writes in DREAM-1..4B MUST be idempotent:

- candidate observation unique by canonical pair + policy fingerprint;
- run IDs are stable UUIDs generated before insert;
- observation replay must not double-increment streaks;
- checkpoint progression is monotonic/CAS-like;
- lease takeover uses fencing epoch.

On ambiguous outcome, retry by operation identity, never by blind additive mutation.

---

## 8. Candidate observation idempotency

### DP-11 — a run can contribute at most one verification observation per pair

`last_verified_run_id` or a dedicated observation identity MUST prevent replay from increasing positive/negative streak more than once.

Required property:

```text
same run_id + same pair + same policy
executed N times
==
executed once
```

Infrastructure failure MUST NOT be recorded as a negative semantic observation.

---

## 9. Checkpoint correctness / no-gap rule

### DP-12 — checkpoint is monotonic and fenced

Checkpoint update must combine:

1. current live lease validation;
2. expected prior checkpoint/CAS condition;
3. monotonic new cursor;
4. update in one statement/transaction.

A stale or reordered checkpoint write must update zero rows and be treated as lost authority/stale progress.

### DP-13 — writes before checkpoint

For each completed source range:

```text
candidate observations durable
        ↓
all required shadow metadata durable
        ↓
checkpoint advances
```

Never advance checkpoint first.

### DP-14 — source no-gap proof

Before implementation acceptance, the chosen fast-lane ordering column must be proven to change for every searchable newly published vector/chunk.

If lifecycle `updated_at` does not guarantee this, introduce/use a publication/vector projection timestamp that does.

Required keyset is total and stable:

```text
(timestamp, access_level, document_id, generation, chunk_id)
```

Rows with the same timestamp are ordered by remaining identity fields.

A test must insert/update rows around the current watermark and prove no permanent omission.

---

## 10. Scheduler overlap and duplicate starts

### DP-15 — local overlapping scheduler calls are rejected

Even the current owner pod may receive another cron invocation while a run is active.

There must be a process-local guard in addition to DB lease ownership:

```java
AtomicBoolean runInProgress
```

or equivalent.

This is a performance/clarity guard; DB fencing remains the correctness boundary.

Second invocation on the same pod records `already_running` and returns.

---

## 11. Rolling deployment and version skew

During a deployment, old and new application versions may coexist.

### DP-16 — semantic algorithm compatibility is fingerprinted

The policy fingerprint MUST include an algorithm/formula version. A materially changed Dream implementation cannot silently write observations into the same policy epoch.

### DP-17 — schema must be expand/compatible for rolling deployment

Migration 026 must be safe to apply before every pod runs Dream-capable code.

Dream runtime flags remain OFF by default, so schema deployment precedes feature activation.

Do not introduce a migration requiring all old pods to understand or write the new Dream tables.

### DP-18 — one run is pinned to one policy fingerprint

A run captures its policy snapshot/fingerprint at start and never mixes observations produced by another application/policy version.

If runtime configuration changes mid-run, the current run stops at a safe batch boundary or finishes its current bounded work under the captured fingerprint. The next run uses the new fingerprint.

---

## 12. Runtime flag convergence across pods

`AppParameterService` uses caching, so 4–6 pods may observe a runtime flag change at slightly different moments.

### DP-19 — safety does not depend on simultaneous flag visibility

Disabling Dream or apply mode must remain safe even if some pods see the new value a few seconds later.

Why this remains safe:

- only the live owner can mutate;
- owner rechecks runtime switches at safe batch/mutation boundaries;
- DB fencing protects stale owners;
- apply-disabled logic is checked immediately before graph mutation.

For emergency stop, operational guidance should allow disabling apply first, then Dream execution.

---

## 13. Connection-pool exhaustion

Dream shares PostgreSQL with online RAG.

### DP-20 — Dream must not block the entire pool

Dream DB operations must be protected by a dedicated semaphore and short acquisition timeout.

If no Dream permit/DB capacity is available, Dream yields rather than waiting indefinitely while holding other resources.

No Dream transaction may span an ANN network wait.

Required ordering:

```text
ANN outside transaction
        ↓
small DB transaction
        ↓
commit
```

Do not:

```text
open transaction
        ↓
perform multiple ANN calls
        ↓
commit minutes later
```

### Acceptance

Under 4–6 pod online load + one Dream owner, connection acquisition latency and request p95/p99 must remain within the agreed quality budget.

---

## 14. Backpressure and admission

### DP-21 — backpressure is fail-soft

`DreamAdmissionController` may pause/yield when shared resources are unhealthy or saturated.

A pause:

- does not increment negative semantic streak;
- does not mark candidate failed;
- does not advance unprocessed checkpoint ranges;
- completes run as `PARTIAL_BUDGET`/controlled stop if deadline is reached.

### Suggested signals

Use only bounded, low-cost signals available locally or from the datasource, such as:

- Dream semaphore saturation;
- JDBC active/pending connection pressure if exposed;
- online executor queue pressure if available;
- run wall-clock budget.

Do not make correctness depend on observability metrics being available.

---

## 15. ANN timeout and retry discipline

### DP-22 — no retry storm

Forward/reverse ANN failures may receive at most a small bounded retry policy, preferably zero or one retry for clearly transient DB errors in v1.

Retries consume the run ANN budget.

All 4–6 pods must never independently retry the same Dream workload because only the owner executes ANN.

Timeout or cancellation -> `UNKNOWN_INFRASTRUCTURE`, never negative semantic evidence.

---

## 16. Cache correctness

Reverse-neighbour cache is an optimization only.

### DP-23 — cache scope is one run/policy epoch

Cache key includes:

```text
ChunkGraphNode
semantic_policy_fingerprint
```

Cache is discarded on run completion/ownership loss/policy change.

It is never shared between pods in v1.

### DP-24 — cached rows are not lifecycle authority

Even when reverse top-K is served from cache, lifecycle must be rechecked before authoritative apply. Cache cannot keep an expired/unpublished relation alive.

---

## 17. Lifecycle races

### DP-25 — discovery eligibility does not grant apply eligibility

A source/target can become expired, retired or unpublished after ANN discovery.

Therefore Dream apply has a mandatory transactional lifecycle recheck including TTL.

For shadow DREAM-4B, candidate state may record that a discovered pair later became `INELIGIBLE`, but lifecycle invalidation is not semantic-negative evidence.

---

## 18. Concurrent graph writers

Dream eventually shares graph rows with ingestion linking, online learning, maintenance and compaction.

Although DREAM-4B is shadow-only, its TDD must prepare for DREAM-5.

### DP-26 — canonical lock order

Any Dream graph mutation in later stages must lock pair nodes in `ChunkGraphNode.compareTo` order and follow the existing advisory-lock convention.

### DP-27 — never hold Dream lease row lock while mutating graph pair

Lease authority should be validated by predicate/token, not by holding a long-running lock on the lease row across graph work. This prevents global serialization and lock amplification.

---

## 19. Shutdown, eviction and node loss

### DP-28 — graceful shutdown is optional for correctness

On SIGTERM:

```text
STOPPING state
-> stop new batches
-> no new checkpoint advancement after stop barrier
-> finish/rollback short DB transaction
-> stop heartbeat
```

Explicit lease release is optional optimization.

Kill -9 correctness is provided by lease expiry + fencing.

### DP-29 — Kubernetes readiness is independent from Dream ownership

A standby pod is healthy.

A Dream run failure alone does not make RAG readiness false unless shared infrastructure itself is unavailable.

Expose Dream health separately for observability.

---

## 20. Run status truthfulness

### DP-30 — success requires live authority at finalization

Before marking a run `SUCCEEDED`, the coordinator verifies it still holds the live lease epoch.

If work completed locally but authority was lost before finalization, outcome is `LOST_OWNERSHIP`, not success.

`PARTIAL_BUDGET` is a normal bounded completion and must identify the stop reason.

---

## 21. Metrics cardinality and replica semantics

### DP-31 — no high-cardinality labels

Never label metrics with:

- owner ID;
- pod name unless bounded infrastructure convention explicitly permits it;
- run UUID;
- document/chunk IDs;
- policy fingerprint.

Use low-cardinality labels (`outcome`, `phase`, `reason`, `direction`, `cache_result`).

Detailed identity remains in structured logs/DB audit.

### DP-32 — standby metrics do not masquerade as work

Non-owner pods may increment acquisition/standby counters, but must not increment source/ANN/candidate work counters.

This makes dashboards reveal accidental duplicate Dream execution.

---

## 22. Security/ACL under distributed operation

### DP-33 — ACL filter exists in SQL, not only Java

ANN source/target queries must include `access_level` in SQL predicates. Java post-filtering alone is insufficient.

Canonical candidate primary identity includes `access_level`.

No cache entry can be reused across ACLs because full node identity includes ACL.

---

## 23. Migration 026 distributed requirements

Migration must include:

- primary/unique constraints for canonical candidate identity;
- unique lease key `(graph_version, semantic_policy_fingerprint)`;
- monotonic non-negative fencing token constraints;
- checkpoint key isolated by graph/policy;
- run status constraints;
- candidate state constraints;
- useful indexes for verification/checkpoint scans.

Migration must not create triggers that perform network/model work or long unbounded graph maintenance.

Runtime feature remains OFF after migration.

---

## 24. Required fault-injection tests

DREAM-1..4B cannot be accepted without the following Testcontainers/integration scenarios.

### Ownership / split brain

1. Start 6 contenders simultaneously -> exactly one authority.
2. Pause owner longer than lease -> second pod takes over -> first pod wakes -> stale checkpoint write rejected.
3. Same pod name, new process UUID -> old epoch cannot renew.
4. Two concurrent takeover attempts on expired lease -> one winner, token increments once.

### Heartbeat

5. Saturate ANN worker executor -> heartbeat still renews.
6. Stop heartbeat -> takeover occurs after lease expiry.
7. DB unavailable during heartbeat -> owner stops mutation/checkpoint authority.

### DB/network ambiguity

8. Simulate client exception after candidate upsert commit -> replay same run -> streak increments once.
9. Simulate exception before checkpoint commit -> run replay has no source gap.
10. DB restart/failover during batch -> no checkpoint beyond durable work.

### Scheduler / deployment

11. Fire cron twice on owner -> one local active run.
12. Rolling restart across 6 pods -> continued takeover with no duplicate authoritative progress.
13. Change runtime policy/flags mid-run -> no mixed fingerprint observations.

### Lifecycle

14. Target expires between forward and reverse ANN -> candidate does not become authoritative active evidence.
15. Target expires between discovery and apply -> apply rejects transactionally.
16. Expired high-similarity row must not displace eligible top-K because TTL predicate is in SQL before LIMIT.

### Resource contention

17. Exhaust Dream DB semaphore -> online requests continue; Dream yields.
18. Apply 4–6 pod synthetic online load + Dream owner -> pool starvation absent and latency inside budget.
19. Force ANN timeouts -> no negative semantic streak.
20. Hit each run budget -> controlled `PARTIAL_BUDGET`, correct last safe checkpoint.

### Shadow guarantee

21. Snapshot `knowledge_chunk_association`; execute complete DREAM-4B run including fail/retry paths; graph snapshot remains logically unchanged.

---

## 25. Implementation review gates

### Gate D1-DIST

DREAM-1 accepted only if:

- flags default OFF;
- policy fingerprint deterministic;
- algorithm version included;
- runtime flag mismatch is safe under cache delay;
- config validates heartbeat/lease relationship.

### Gate D2-DIST

DREAM-2 accepted only if:

- all persistence operations are idempotent;
- canonical pair uniqueness survives concurrent insert;
- replay does not double-increment streak;
- checkpoint supports monotonic fenced CAS;
- migration supports rolling deployment.

### Gate D3-DIST

DREAM-3 accepted only if:

- 6-way lease race has one owner;
- DB time is authoritative;
- heartbeat executor is isolated;
- heartbeat failure propagates to coordinator;
- stale epoch cannot mutate/advance;
- DB outage causes conservative authority loss;
- kill -9/takeover works.

### Gate D4A-DIST

DREAM-4A accepted only if lifecycle eligibility including TTL is enforced in SQL before ANN LIMIT and again at future apply boundary.

### Gate D4B-DIST

DREAM-4B accepted only if:

- only owner executes ANN;
- reciprocal lookup uses current target embedding;
- cache is bounded/run-local;
- timeout/budget/DB failure never becomes semantic negative;
- source watermark passes no-gap tests;
- all budgets are pre-acquired;
- shadow run cannot mutate online graph;
- online traffic wins under saturation.

---

## 26. Explicit anti-patterns

The following implementations are rejected during review:

```text
@Scheduled -> every pod performs Dream work
```

```text
SELECT lease; then UPDATE lease
```

```text
lease validity decided by Instant.now()
```

```text
heartbeat on same saturated ANN executor
```

```text
catch(Exception) -> negative_streak++
```

```text
checkpoint update without live lease/fencing predicate
```

```text
watermark advanced before durable candidate write
```

```text
ANN query performed inside long graph transaction
```

```text
TTL filtered in Java after ANN LIMIT
```

```text
retry additive streak/counter mutation without operation identity
```

```text
pod name reused as sole owner identity
```

```text
Dream metrics labelled by chunk/document/run UUID
```

---

## 27. Final distributed-production invariant

The release contract for DREAM-1..4B is:

> Any single pod may pause, restart, lose network access, lose its lease, run stale code during a rolling deployment, or replay its last batch without creating duplicate semantic evidence, skipping durable source work, corrupting authoritative graph state, or degrading online RAG beyond the agreed resource budget.

Only after that invariant is demonstrated should the project proceed to DREAM-5 semantic graph mutation.
