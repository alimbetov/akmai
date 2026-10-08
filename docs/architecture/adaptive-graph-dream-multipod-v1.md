# Adaptive Graph Dream — multi-pod deployment profile

Classification: **PROPOSED / NORMATIVE FOR DREAM V1 MULTI-POD DEPLOYMENT**  
Target branch: `feature/adaptive-graph-dream`  
Deployment assumption: **4–6 application pods**  
Date: 2026-10-08

This document extends `adaptive-graph-dream-pragmatic-v1.md` for the expected production topology where AkmAI runs as one microservice with 4–6 replicas.

Where this document is stricter than the generic Dream specification, this document wins for multi-pod Dream v1.

---

## 1. Deployment model

Assume one logical AkmAI service deployed as 4–6 interchangeable pods:

```text
                  ┌─────────────┐
                  │ PostgreSQL  │
                  │ + pgvector  │
                  └──────┬──────┘
                         │
      ┌──────────────────┼──────────────────┐
      │                  │                  │
┌─────▼─────┐      ┌─────▼─────┐      ┌─────▼─────┐
│ AkmAI P1  │      │ AkmAI P2  │ ...  │ AkmAI P6  │
│ requests  │      │ requests  │      │ requests  │
│ Dream     │      │ Dream     │      │ Dream     │
└───────────┘      └───────────┘      └───────────┘
```

All pods contain Dream code, but this does **not** mean all pods execute the same Dream work.

The default v1 execution model is:

```text
4–6 eligible pods
      ↓
cluster-wide Dream lease election
      ↓
1 active Dream coordinator
      ↓
0–5 standby pods
```

This is deliberate. Dream v1 optimizes for correctness, bounded load and operational simplicity before horizontal Dream throughput.

---

## 2. Single active coordinator is the v1 default

For Dream v1, only one pod may own the global Dream coordinator lease at a time.

Why:

- reciprocal ANN already creates substantial vector/DB load;
- all pods also serve interactive requests;
- multi-writer Dream would increase race surface for candidate creation, watermark movement and semantic-prior mutation;
- 4–6 request-serving pods must not accidentally multiply nightly ANN traffic by 4–6x.

Therefore:

```text
@Scheduled fires on every pod
        ↓
tryAcquireDreamLease()
        ↓
exactly one owner continues
        ↓
all other pods return immediately
```

Standby pods remain available for automatic takeover after lease expiry.

---

## 3. Pod identity

Every pod participating in Dream ownership MUST have a stable runtime owner identity for its lifetime.

Recommended form:

```text
owner_id = <deployment>/<pod-name>/<process-start-uuid>
```

Examples:

```text
akmai-prod/akmai-7df8c6b4c9-x2l7p/1f2d...
akmai-prod/akmai-7df8c6b4c9-p9m4k/7ca1...
```

Do not use only hostname or replica ordinal because a restarted pod must not accidentally inherit stale ownership semantics.

The owner identity is persisted only as operational fencing metadata, not as business data.

---

## 4. Cluster lease and fencing

Dream requires one cluster-global ownership row per semantic policy / Dream lane.

Minimum lease state:

```text
lease_name
owner_id
lease_until
fencing_token
updated_at
```

For v1 a single global lease is sufficient:

```text
lease_name = adaptive-graph-dream-v1
```

Ownership rules:

1. only the current owner may process apply-capable Dream work;
2. acquisition/takeover increments `fencing_token`;
3. every graph mutation and watermark advancement carries the current token;
4. stale owners fail closed;
5. lease ownership is checked transactionally at mutation boundaries.

A stale pod must never be able to apply candidate state after another pod has taken over.

---

## 5. Independent heartbeat

Lease renewal MUST be independent of ANN and database batch processing.

Required model:

```text
active Dream pod
   ├── coordinator worker
   │     ├── source enumeration
   │     ├── forward ANN
   │     ├── reverse ANN
   │     ├── candidate persistence
   │     └── optional semantic apply
   │
   └── lease heartbeat
         └── renew approximately every lease_duration / 3
```

Recommended starting values:

```text
lease_duration = 90s
heartbeat_interval = 20–30s
```

These are operational defaults, not immutable constants.

On heartbeat failure or fencing-token loss:

```text
stop new batches
stop apply operations
stop watermark advancement
finish/abort current DB transaction safely
release in-memory caches
exit Dream run
```

Interactive request serving by the pod may continue if otherwise healthy.

---

## 6. Do not use pod count as Dream parallelism

A 6-pod service MUST NOT imply 6 concurrent Dream workers against the same corpus by default.

Forbidden v1 pattern:

```text
6 pods
× 10,000 sources
× reciprocal top-K
= accidental 6x ANN / DB load
```

Dream concurrency is configured independently from Kubernetes replica count.

Recommended v1:

```text
dream_active_coordinators = 1
dream_worker_parallelism = small bounded value inside owner pod
```

Suggested first calibration range:

```text
worker_parallelism = 1..4
```

Increase only from measured DB/vector headroom.

---

## 7. Resource fairness with interactive traffic

Dream runs in the same microservice as online RAG traffic, therefore request-path work has priority.

The active Dream pod must apply local and cluster-level backpressure.

At minimum pause/yield Dream when one or more conditions are true:

```text
request executor saturation high
JDBC pool utilization high
DB latency above threshold
ANN/vector query latency above threshold
CPU above threshold
memory pressure high
shutdown/readiness transition in progress
```

The exact signals may initially be simple, but the policy is normative:

> Dream consumes spare capacity; it does not compete aggressively with interactive retrieval.

Recommended scheduling behaviour:

```text
healthy spare capacity
    -> process next Dream batch

moderate pressure
    -> reduce local worker parallelism / sleep between batches

high pressure
    -> checkpoint and pause run
```

---

## 8. Connection pool isolation

Because Dream and request traffic share the same PostgreSQL cluster, Dream must not be allowed to exhaust the pod's JDBC pool.

At minimum enforce one of:

1. a dedicated bounded Dream semaphore over shared JDBC access; or
2. a dedicated small Dream datasource/pool.

Preferred initial approach if operationally simple:

```text
shared datasource
+ Dream DB concurrency semaphore
```

Example policy for a pod with pool size `P`:

```text
Dream may consume at most min(2, floor(P * 0.20)) concurrent DB operations
```

Exact values require load testing.

The hard invariant is that request threads retain connection headroom while Dream is active.

---

## 9. ANN concurrency isolation

Forward and reverse ANN lookups must use explicit bounded concurrency.

Required controls:

```text
max_inflight_forward_ann
max_inflight_reverse_ann
max_ann_queries_per_run
max_reverse_ann_queries_per_run
```

Reverse lookup deduplication/cache remains mandatory.

For 4–6 pods, cache is intentionally **owner-local** in v1:

```text
bounded LRU/Caffeine cache inside active Dream pod
```

Do not introduce distributed cache solely for Dream v1. Lease ownership ensures one active coordinator, so distributed reverse-neighbour caching adds complexity without a clear first-version benefit.

---

## 10. Watermark ownership

The global Dream watermark is cluster state, not pod-local state.

Rules:

- only the current fenced owner may advance it;
- watermark advances only after the relevant batch is durably persisted;
- losing ownership before commit means watermark does not advance;
- takeover resumes from the last durable cursor;
- processing must be idempotent so replaying the final batch is safe.

Required transaction ordering:

```text
process batch
   ↓
persist candidate observations
   ↓
optional fenced semantic apply
   ↓
advance fenced watermark
   ↓
commit
```

If candidate persistence and watermark cannot share one transaction for practical reasons, use idempotent candidate upserts and advance watermark last.

---

## 11. Candidate-store concurrency

Even with one active Dream coordinator, online ingestion and graph learning continue on all 4–6 pods.

Therefore candidate and semantic-prior operations must assume concurrent database activity from:

```text
Dream owner pod
online learning on P1..P6
ingestion semantic linking on P1..P6
graph maintenance scheduler
compaction/cleanup
```

Required invariants:

- canonical Dream pair unique under concurrent insert;
- same canonical node ordering everywhere;
- graph semantic mutation uses the same node lock ordering as existing semantic seeding;
- online evidence counters are never overwritten by Dream upsert;
- semantic retirement cannot delete a row that acquired online evidence between verification and apply;
- final state is re-read/revalidated transactionally before semantic clear/removal.

---

## 12. Interaction with Adaptive Graph maintenance

Dream and normal graph maintenance may overlap in wall-clock time on different pods.

This is expected.

Dream must not assume maintenance is stopped.

Safe responsibility split:

```text
Dream
  -> semantic candidate/provenance
  -> semantic prior set/degrade/clear

AdaptiveGraphMaintenance
  -> weight scoring
  -> WARM/HOT/CANDIDATE/DECAYED lifecycle
  -> quotas/decay/pruning
```

Any shared edge mutation must use compatible lock ordering and short transactions.

A concurrency acceptance test must execute:

```text
Dream semantic apply/retirement
+ online reinforcement
+ maintenance
+ semantic ingestion seed
```

against the same edge/node set and assert:

```text
no lost online counters
no asymmetric pair corruption
no stale-owner apply
acceptable deadlock/abort rate
```

If deadlocks are reproducible, unify the lock protocol before enabling Dream apply.

---

## 13. Scheduler behaviour across 4–6 pods

All pods may have the same scheduler configuration.

Recommended pattern:

```text
@Scheduled
public void runDreamTick() {
    if (!dreamEnabled) return;
    if (!lease.tryAcquireOrRenew(...)) return;
    coordinator.runBoundedCycle(...);
}
```

The scheduler tick itself must be cheap for standby pods.

Standby pods must not:

- enumerate sources;
- execute ANN;
- allocate large Dream caches;
- write candidate observations;
- mutate graph state.

They only participate in lease acquisition/takeover.

---

## 14. Rolling deployment and pod termination

Dream must be safe during Kubernetes rolling updates.

On graceful shutdown / preStop:

1. stop starting new Dream batches;
2. mark local coordinator as draining;
3. finish or rollback active short transaction;
4. stop heartbeat;
5. optionally release lease early;
6. allow another pod to acquire ownership.

Correctness must not depend on early release; lease expiry + fencing remains the final safety mechanism.

Recommended termination grace period must exceed the maximum bounded Dream transaction duration, not the duration of the whole Dream run.

---

## 15. Readiness and liveness semantics

Dream failure alone should not necessarily make the AkmAI request-serving pod unready.

Separate health dimensions:

```text
request-serving readiness
Dream subsystem health
```

Examples:

- Dream lease unavailable because another pod owns it -> healthy standby;
- one Dream run fails but request path is healthy -> pod remains ready, Dream metric/alert fires;
- database unavailable -> normal application readiness policy applies;
- repeated Dream fencing failures -> Dream degraded alert, not automatic pod restart loop.

Do not use liveness probes to restart pods merely because a Dream cycle failed.

---

## 16. Horizontal scaling policy

Increasing AkmAI replicas from 4 to 6 should improve online capacity and Dream takeover availability, but MUST NOT automatically increase Dream workload.

Therefore these dimensions remain separate:

```text
service replica count       = 4..6
Dream coordinator count     = 1
Dream local worker count    = configurable bounded value
Dream source/run budgets    = cluster-level constants
```

This prevents autoscaling the online service from accidentally multiplying offline work.

---

## 17. Future parallel Dream mode — explicitly out of v1

If one coordinator later becomes insufficient for corpus size, parallel Dream may be added using deterministic shards.

Possible future model:

```text
N Dream shards
hash(access_level, document_id, generation, chunk_id) % N
```

with one fenced lease per shard.

But this is **not Dream v1**.

Reasons to defer:

- cross-shard reciprocal KNN still touches shared vector space;
- candidate pair ownership must be canonicalized across shards;
- degree caps and semantic retirement become more concurrent;
- DB/ANN saturation may dominate before CPU parallelism helps.

Only introduce sharded coordinators if measured single-owner throughput cannot meet the required rescan horizon within the allowed low-traffic window.

---

## 18. Recommended multi-pod configuration contract

Add or reserve configuration equivalent to:

```yaml
akmai:
  adaptive-graph:
    dream:
      lease-duration: ${AKMAI_ADAPTIVE_GRAPH_DREAM_LEASE_DURATION:90s}
      heartbeat-interval: ${AKMAI_ADAPTIVE_GRAPH_DREAM_HEARTBEAT_INTERVAL:25s}
      worker-parallelism: ${AKMAI_ADAPTIVE_GRAPH_DREAM_WORKER_PARALLELISM:2}
      max-inflight-forward-ann: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_INFLIGHT_FORWARD_ANN:2}
      max-inflight-reverse-ann: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_INFLIGHT_REVERSE_ANN:2}
      db-concurrency: ${AKMAI_ADAPTIVE_GRAPH_DREAM_DB_CONCURRENCY:2}
```

Validation:

```text
heartbeat_interval < lease_duration / 2
recommended heartbeat_interval <= lease_duration / 3
worker_parallelism >= 1
all inflight/concurrency limits >= 1
Dream DB concurrency must remain below total pool capacity
```

Defaults must remain conservative.

---

## 19. Multi-pod metrics

Required operational metrics:

```text
adaptive_graph_dream_lease_acquire_total{outcome}
adaptive_graph_dream_lease_takeover_total
adaptive_graph_dream_lease_heartbeat_total{outcome}
adaptive_graph_dream_fencing_reject_total{operation}
adaptive_graph_dream_owner_info{pod,owner_id}
adaptive_graph_dream_standby_ticks_total{pod}
adaptive_graph_dream_worker_inflight{type}
adaptive_graph_dream_db_inflight
adaptive_graph_dream_backpressure_total{reason}
adaptive_graph_dream_paused_seconds{reason}
adaptive_graph_dream_batch_duration_seconds
adaptive_graph_dream_takeover_resume_total
adaptive_graph_dream_replayed_batches_total
```

Dashboard should make it obvious that exactly one coordinator is active.

Alert candidates:

- no Dream owner for an unexpectedly long period while Dream is enabled;
- more than one successful owner reported for same fencing generation;
- repeated fencing rejects from the same pod;
- heartbeat failure burst;
- Dream repeatedly paused by DB/request pressure;
- rescan horizon exceeding target.

---

## 20. Multi-pod acceptance tests

Before apply mode is enabled, Testcontainers/integration coverage must include at least:

### Election

```text
start 6 logical Dream coordinators
→ exactly one acquires global lease
```

### Takeover

```text
P1 owns lease
P1 heartbeat stops
lease expires
P2 acquires token N+1
P1 attempts stale apply
→ rejected
```

### Long batch heartbeat

```text
batch duration > lease duration
heartbeat remains healthy
other 5 pods attempt takeover
→ none succeeds
```

### Rolling restart

```text
owner pod terminates mid-run
new pod acquires after release/expiry
resumes from durable watermark
no missing source range
no harmful duplicate candidate state
```

### Concurrent online learning

```text
Dream verifies/retires A↔B
another pod records grounded online evidence for A↔B
→ online evidence survives
→ Dream cannot delete learned counters
```

### Maintenance overlap

```text
Dream semantic mutation
+ graph maintenance
+ online reinforcement
+ ingestion semantic seed
→ no pair asymmetry / lost counters
```

### Load isolation

```text
4–6 pods serving synthetic RAG traffic
1 Dream owner active
→ request p95/p99 and error rate stay within agreed budget
→ JDBC pool retains headroom
```

---

## 21. Production rollout for 4–6 pods

Recommended sequence:

```text
M0
4–6 pods
Dream disabled

M1
Dream enabled, apply disabled
single-owner election only
verify lease/standby behaviour

M2
shadow discovery with worker_parallelism=1
measure DB/ANN/request impact

M3
increase worker_parallelism only if headroom exists
calibrate budgets and rescan horizon

M4
apply enabled while online graph expansion remains disabled
verify concurrent online learning/maintenance safety

M5
graph shadow evaluation

M6
small online canary

M7
broader rollout only after sustained utility and latency evidence
```

Replica autoscaling during M1–M7 must not change Dream source/run budgets automatically.

---

## 22. Key multi-pod design decision

For the expected 4–6 pod AkmAI deployment, Dream v1 deliberately uses:

```text
many request-serving replicas
        +
one fenced Dream coordinator
        +
small bounded local Dream parallelism
        +
automatic standby takeover
```

rather than:

```text
every pod independently dreams
```

This preserves the intended brain-inspired offline consolidation model without turning pod replication into uncontrolled ANN/database amplification.

The pragmatic rule is:

> Scale online serving with pod count. Scale Dream only from measured offline throughput need.
