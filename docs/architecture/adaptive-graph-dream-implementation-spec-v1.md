# Adaptive Graph Dream — implementation specification v1

Classification: **PROPOSED / NORMATIVE IMPLEMENTATION CONTRACT**  
Target branch: `feature/adaptive-graph-dream`  
Baseline: `main@381b9c6cbf835daf2b301280171c049596e430a3`  
Deployment profile: **4–6 AkmAI pods, one active Dream coordinator in v1**  
Date: 2026-10-08

This document is the implementation-level technical specification for Adaptive Graph Dream v1.

It consolidates and resolves ambiguities between:

- `adaptive-graph-dream-cycle.md` — conceptual design;
- `adaptive-graph-dream-pragmatic-v1.md` — pragmatic/safety profile;
- `adaptive-graph-dream-multipod-v1.md` — 4–6 pod deployment profile.

If those documents conflict with this implementation specification, **this document wins for Dream v1 implementation**.

The purpose of this specification is to make implementation deterministic: class responsibilities, database schema, state transitions, transaction boundaries, fencing rules, resource limits, configuration, runtime flags, algorithms, metrics, test gates and rollout criteria are defined below.

---

## 1. Goal

Adaptive Graph Dream is a bounded offline semantic-consolidation subsystem for the existing AkmAI Adaptive Association Graph.

Its job is to discover and periodically re-evaluate plausible semantic associations that may not have been learned from request-time evidence yet.

The fundamental authority model is:

```text
semantic similarity
        ↓
Dream hypothesis
        ↓
reciprocal-neighbour verification
        ↓
semantic confidence
        ↓
optional CANDIDATE semantic prior
        ↓
real grounded online usage
        ↓
WARM/HOT proof of retrieval utility
```

Dream is not an authoritative source of knowledge and is not allowed to convert repeated offline similarity observations into user-evidence counters.

---

## 2. Non-goals for v1

Dream v1 MUST NOT implement:

- multi-hop Dream traversal;
- cross-ACL associations;
- automatic ontology mutation;
- automatic Reference Graph mutation;
- cross-language association by default;
- LLM/NLI validation inside the nightly batch;
- automatic threshold learning;
- distributed multi-owner Dream sharding;
- Query Memory driven Dream discovery;
- Dream-to-Dream reinforcement of online evidence;
- direct `WARM`/`HOT` promotion;
- new online retrieval ranking logic;
- a graph database dependency;
- unbounded full-corpus all-pairs comparison.

These items require a later design/replay gate.

---

## 3. Existing AkmAI boundaries that remain authoritative

Dream extends, but does not replace, existing AkmAI components.

Existing online graph responsibilities remain:

```text
ADAPTIVE_GRAPH_LEARNING_ENABLED
  -> grounded request-time association evidence

ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
  -> learned score calculation, hysteresis, quotas, decay, cleanup

ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED
  -> observe graph candidates without request-time effect

ADAPTIVE_GRAPH_EXPANSION_ENABLED
  -> allow eligible graph neighbours into retrieval

ADAPTIVE_GRAPH_COMPETITION_ENABLED
  -> allow eligible graph candidates to compete with base retrieval
```

Existing semantic ingestion responsibility remains:

```text
IngestionSemanticLinker
  -> initial semantic seeding immediately after generation publication
```

Dream responsibility is specifically:

```text
cross-time semantic discovery
+ reciprocal verification
+ semantic-prior revalidation
+ semantic-prior retirement
```

`knowledge_chunk_association` remains the only online Adaptive Association Graph store.

Dream MUST NOT introduce a second online graph.

---

## 4. Hard invariants

The following invariants are release-blocking.

### DGI-01 — ACL isolation

A Dream pair is legal only if:

```text
A.access_level == B.access_level == candidate.access_level
```

Cross-ACL candidate creation, persistence and apply are forbidden.

### DGI-02 — generation identity

Dream node identity is exactly:

```text
(access_level, document_id, generation, chunk_id)
```

A new document generation is a different node.

Dream MUST NOT silently transfer semantic evidence from one generation to another.

### DGI-03 — lifecycle eligibility

Every source and target used by Dream MUST satisfy:

```sql
lifecycle_status = 'READY'
AND retention_status = 'ACTIVE'
AND published_generation = node.generation
AND (expires_at IS NULL OR expires_at > clock_timestamp())
```

This predicate is required:

1. at source enumeration;
2. in forward ANN search;
3. in reciprocal verification;
4. again inside the transaction that applies or updates a semantic prior.

A request-path final fence is not sufficient for Dream.

### DGI-04 — semantic evidence is not online evidence

Dream MUST NOT increment or synthesize:

```text
support_count
context_count
citation_count
distinct_query_support
query_support_sketch
last_reinforced_at
```

Dream MUST NOT invoke general online reinforcement APIs.

### DGI-05 — no Dream promotion authority

Dream MUST NOT directly set a graph edge to `WARM` or `HOT`.

Dream may create/update only a semantic `CANDIDATE` prior.

Promotion authority remains in existing learned evidence + maintenance semantics.

### DGI-06 — learned evidence survives semantic retirement

If semantic evidence weakens while online learned evidence exists, Dream may clear/degrade semantic state but MUST preserve all online learned counters and the learned relation.

### DGI-07 — bounded work

Every run is bounded by source, ANN-query, DB-row, time, concurrency and batch limits.

Budget exhaustion is a normal controlled outcome, not a failure.

### DGI-08 — fenced single ownership in v1

With 4–6 pods, at most one pod may own Dream coordination/apply authority for the active Dream lease.

Stale owners cannot:

- apply semantic priors;
- retire semantic priors;
- advance a watermark;
- finalize a run as successful.

### DGI-09 — shadow cannot mutate the online graph

When:

```text
ADAPTIVE_GRAPH_DREAM_ENABLED=true
ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED=false
```

Dream may persist Dream-internal state and telemetry but `knowledge_chunk_association` MUST remain unchanged by Dream.

### DGI-10 — online traffic wins

Dream is spare-capacity background work.

If resource-pressure thresholds are exceeded, Dream reduces concurrency, yields or pauses before interactive RAG traffic is degraded beyond configured budgets.

---

## 5. Required changes to existing code before Dream apply

Dream implementation depends on two existing semantic boundaries that require hardening.

### 5.1 `SemanticNeighborSearchRepository`

Current semantic neighbour search must be extended so every candidate row also satisfies TTL eligibility:

```sql
AND (
    l.expires_at IS NULL
    OR l.expires_at > clock_timestamp()
)
```

The repository MUST continue enforcing:

- active embedding profile;
- configured vector dimensions;
- same ACL;
- published generation;
- READY lifecycle;
- ACTIVE retention;
- language policy;
- top-K bound;
- similarity threshold.

The implementation SHOULD expose a Dream-compatible result that includes reciprocal-rank material without duplicating lifecycle SQL in a second repository.

### 5.2 `SemanticAssociationSeedRepository`

`lockPublishedGeneration(...)` must also reject expired lifecycle rows using the same TTL predicate.

However Dream MUST NOT use the monotonic ingestion seeding operation as its only mutation API, because ingestion currently retains the greatest observed semantic similarity.

Dream requires a restricted semantic mutation boundary described in section 12.

---

## 6. Configuration model

Dream settings MUST be added under the existing `AdaptiveGraphProperties` configuration root.

Target shape:

```java
@ConfigurationProperties("akmai.adaptive-graph")
public record AdaptiveGraphProperties(
    boolean learningEnabled,
    boolean maintenanceEnabled,
    boolean shadowExpansionEnabled,
    boolean expansionEnabled,
    boolean dreamEnabled,
    int graphVersion,
    Learning learning,
    ShadowExpansion shadowExpansion,
    Scoring scoring,
    Maintenance maintenance,
    BandQuotas quotas,
    Storage storage,
    Dream dream
) { ... }
```

Target nested record:

```java
public record Dream(
    boolean applyEnabled,
    String cron,
    String zone,
    int topK,
    double candidateThreshold,
    double activationThreshold,
    double retentionThreshold,
    double forgettingThreshold,
    int maxNewEdgesPerChunk,
    int maxSourcesPerRun,
    int rescanSourcesPerRun,
    int batchSize,
    int negativeStreakForForgetting,
    boolean decayEnabled,
    int maxAnnQueriesPerRun,
    int maxReverseAnnQueriesPerRun,
    long maxDbRowsTouchedPerRun,
    Duration maxRunDuration,
    Duration queryTimeout,
    Duration transactionTimeout,
    Duration leaseDuration,
    Duration heartbeatInterval,
    int maxDbConcurrency,
    int maxForwardAnnConcurrency,
    int maxReverseAnnConcurrency,
    int reverseCacheMaximumSize,
    String semanticPolicyVersion
) { ... }
```

Final field names may follow project Java naming conventions, but their semantics MUST remain equivalent.

### 6.1 Proposed `application.yml`

```yaml
akmai:
  adaptive-graph:
    dream-enabled: ${AKMAI_ADAPTIVE_GRAPH_DREAM_ENABLED:false}
    dream:
      apply-enabled: ${AKMAI_ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED:false}
      cron: "${AKMAI_ADAPTIVE_GRAPH_DREAM_CRON:0 0 3 * * *}"
      zone: ${AKMAI_ADAPTIVE_GRAPH_DREAM_ZONE:UTC}

      top-k: ${AKMAI_ADAPTIVE_GRAPH_DREAM_TOP_K:32}
      candidate-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_CANDIDATE_THRESHOLD:0.88}
      activation-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_ACTIVATION_THRESHOLD:0.94}
      retention-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_RETENTION_THRESHOLD:0.90}
      forgetting-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_FORGETTING_THRESHOLD:0.86}

      max-new-edges-per-chunk: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_NEW_EDGES_PER_CHUNK:3}
      max-sources-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_SOURCES_PER_RUN:10000}
      rescan-sources-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_RESCAN_SOURCES_PER_RUN:1000}
      batch-size: ${AKMAI_ADAPTIVE_GRAPH_DREAM_BATCH_SIZE:200}
      negative-streak-for-forgetting: ${AKMAI_ADAPTIVE_GRAPH_DREAM_NEGATIVE_STREAK:3}
      decay-enabled: ${AKMAI_ADAPTIVE_GRAPH_DREAM_DECAY_ENABLED:true}

      max-ann-queries-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_ANN_QUERIES:100000}
      max-reverse-ann-queries-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_REVERSE_ANN_QUERIES:90000}
      max-db-rows-touched-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_DB_ROWS:1000000}
      max-run-duration: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_RUN_DURATION:2h}
      query-timeout: ${AKMAI_ADAPTIVE_GRAPH_DREAM_QUERY_TIMEOUT:5s}
      transaction-timeout: ${AKMAI_ADAPTIVE_GRAPH_DREAM_TRANSACTION_TIMEOUT:30s}

      lease-duration: ${AKMAI_ADAPTIVE_GRAPH_DREAM_LEASE_DURATION:90s}
      heartbeat-interval: ${AKMAI_ADAPTIVE_GRAPH_DREAM_HEARTBEAT_INTERVAL:25s}

      max-db-concurrency: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_DB_CONCURRENCY:2}
      max-forward-ann-concurrency: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_FORWARD_ANN_CONCURRENCY:2}
      max-reverse-ann-concurrency: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_REVERSE_ANN_CONCURRENCY:2}
      reverse-cache-maximum-size: ${AKMAI_ADAPTIVE_GRAPH_DREAM_REVERSE_CACHE_SIZE:10000}

      semantic-policy-version: ${AKMAI_ADAPTIVE_GRAPH_DREAM_POLICY_VERSION:dream-v1}
```

The numeric defaults above are conservative implementation starting points, not validated production thresholds. They MUST remain shadow-only until measured.

### 6.2 Configuration validation

Startup validation MUST reject invalid combinations.

Required validation:

```text
2 <= topK <= 256
0 <= all thresholds <= 1
candidateThreshold <= forgettingThreshold
forgettingThreshold < retentionThreshold
retentionThreshold < activationThreshold
1 <= maxNewEdgesPerChunk <= 16
maxSourcesPerRun > 0
rescanSourcesPerRun >= 0
1 <= batchSize <= 1000
negativeStreakForForgetting >= 2
max* budgets > 0
maxRunDuration > 0
queryTimeout > 0
transactionTimeout > 0
leaseDuration > 0
heartbeatInterval > 0
heartbeatInterval <= leaseDuration / 3
maxDbConcurrency >= 1
maxForwardAnnConcurrency >= 1
maxReverseAnnConcurrency >= 1
reverseCacheMaximumSize >= topK
semanticPolicyVersion nonblank
cron parses
zone parses
```

`dream.apply-enabled=true` while `dream-enabled=false` SHOULD fail fast rather than silently do nothing.

---

## 7. Runtime switches

Add to `AppParameterKey`:

```java
ADAPTIVE_GRAPH_DREAM_ENABLED(
    "akmai.adaptive-graph.dream-enabled",
    "Enable bounded Adaptive Graph Dream discovery and verification"
),
ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED(
    "akmai.adaptive-graph.dream.apply-enabled",
    "Allow Dream to apply or retire semantic priors in the adaptive graph"
)
```

Runtime semantics:

| Dream | Apply | Behaviour |
|---|---|---|
| false | false | no Dream run |
| false | true | invalid configuration / fail fast |
| true | false | shadow discovery/verification only |
| true | true | Dream may mutate semantic priors, still no WARM/HOT authority |

Runtime flag evaluation must use the existing `AppParameterService` convention where appropriate so operations can disable Dream without redeploying pods.

If `DREAM_ENABLED` becomes false during a run, the active coordinator SHOULD stop after the current safe batch boundary.

If `DREAM_APPLY_ENABLED` becomes false during a run, subsequent graph mutations MUST stop; Dream-internal shadow observations may continue.

---

## 8. Semantic policy fingerprint

Dream observations are valid only under a known semantic policy.

Persist:

```text
semantic_policy_version
semantic_policy_fingerprint
```

`semantic_policy_fingerprint` MUST be SHA-256 of deterministic canonical input containing at least:

```text
embedding profile/model identifier
vector dimensions
vector distance semantics
language policy
topK
candidate threshold
activation threshold
retention threshold
forgetting threshold
confidence formula version
normalization semantics
Dream algorithm version
```

The canonical representation MUST have deterministic ordering and formatting.

Changing any material input produces a new fingerprint.

Old observations under another fingerprint may remain for audit but MUST NOT be counted as current verification evidence.

A policy change resets the fast-lane watermark for the new policy fingerprint and triggers bounded rescan coverage; it MUST NOT perform an unbounded immediate full-corpus scan.

---

## 9. Database migration

The next migration on the reviewed baseline is:

```text
026-adaptive-graph-dream.sql
```

It MUST be included after `025-query-memory-policy-isolation.sql` in `db.changelog-greenfield.yaml`.

Migration 026 defines the canonical Dream persistence model described below.

---

## 10. Dream candidate table

Table:

```text
knowledge_chunk_dream_candidate
```

One row represents one canonical undirected pair under one graph version and semantic policy fingerprint.

### 10.1 Required columns

```sql
access_level BIGINT NOT NULL,
node_a_document_id VARCHAR(100) NOT NULL,
node_a_generation BIGINT NOT NULL,
node_a_chunk_id VARCHAR(100) NOT NULL,
node_b_document_id VARCHAR(100) NOT NULL,
node_b_generation BIGINT NOT NULL,
node_b_chunk_id VARCHAR(100) NOT NULL,
graph_version INTEGER NOT NULL,
semantic_policy_version VARCHAR(100) NOT NULL,
semantic_policy_fingerprint VARCHAR(64) NOT NULL,

state VARCHAR(16) NOT NULL,
forward_similarity DOUBLE PRECISION,
reverse_similarity DOUBLE PRECISION,
forward_rank INTEGER,
reverse_rank INTEGER,
mutual_knn BOOLEAN NOT NULL DEFAULT FALSE,
confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
positive_streak INTEGER NOT NULL DEFAULT 0,
negative_streak INTEGER NOT NULL DEFAULT 0,

embedding_profile_id VARCHAR(200) NOT NULL,
discovery_run_id UUID,
last_verified_run_id UUID,
discovery_reason VARCHAR(64),
activation_reason VARCHAR(64),
retirement_reason VARCHAR(64),

first_seen_at TIMESTAMPTZ NOT NULL,
last_seen_at TIMESTAMPTZ NOT NULL,
last_verified_at TIMESTAMPTZ,
activated_at TIMESTAMPTZ,
retired_at TIMESTAMPTZ,
updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
```

### 10.2 State values

Allowed states:

```text
CANDIDATE
ACTIVE
STALE
REJECTED
```

`ACTIVE` means semantically activated inside Dream state. It does **not** mean Adaptive Graph band `WARM/HOT`.

### 10.3 Canonical ordering

For every pair:

```text
node_a < node_b
```

using the same deterministic `ChunkGraphNode.compareTo` ordering used elsewhere in Adaptive Graph logic.

Self-pairs are forbidden.

### 10.4 Primary/unique identity

Unique key:

```text
(
 access_level,
 node_a_document_id,
 node_a_generation,
 node_a_chunk_id,
 node_b_document_id,
 node_b_generation,
 node_b_chunk_id,
 graph_version,
 semantic_policy_fingerprint
)
```

`semantic_policy_version` is descriptive and must not replace the fingerprint in uniqueness.

### 10.5 Constraints

Required DB checks:

- positive `access_level`;
- positive generations;
- positive graph version;
- no self pair;
- allowed state;
- similarity/confidence in `[0,1]` when non-null;
- positive ranks when non-null;
- non-negative streaks;
- 64-char lowercase/normalized SHA-256 fingerprint format or equivalent strict validation.

### 10.6 Indexes

At minimum:

```text
(policy_fingerprint, state, last_verified_at)
(access_level, state, updated_at)
(policy_fingerprint, updated_at)
```

Indexes should support candidate verification and retirement scans without full-table scans.

---

## 11. Dream run / lease / checkpoint persistence

Dream v1 needs durable cluster ownership and resumability.

Prefer separate tables rather than overloading candidate rows.

### 11.1 `adaptive_graph_dream_lease`

Single logical lease per graph version + semantic policy fingerprint.

Required data:

```text
graph_version
semantic_policy_fingerprint
owner_id
lease_until
fencing_token
updated_at
```

Unique key:

```text
(graph_version, semantic_policy_fingerprint)
```

Acquisition/takeover increments `fencing_token` monotonically.

### 11.2 `adaptive_graph_dream_checkpoint`

Required data:

```text
graph_version
semantic_policy_fingerprint
fast_watermark_updated_at
fast_watermark_access_level
fast_watermark_document_id
fast_watermark_generation
fast_watermark_chunk_id
rescan_cursor
last_successful_run_id
last_completed_at
fencing_token
updated_at
```

The exact serialized form of `rescan_cursor` may differ, but it must provide deterministic bounded rotation of old nodes.

Checkpoint advancement is fenced.

### 11.3 `adaptive_graph_dream_run`

Required for observability/audit.

Suggested fields:

```text
run_id UUID PK
owner_id
fencing_token
graph_version
semantic_policy_version
semantic_policy_fingerprint
started_at
completed_at
status
sources_fast
sources_rescan
forward_ann_queries
reverse_ann_queries
candidates_seen
candidates_mutual
candidates_activated
candidates_applied
candidates_retired
db_rows_touched
stop_reason
error_class
```

Allowed run status:

```text
RUNNING
SUCCEEDED
PARTIAL_BUDGET
FAILED
LOST_OWNERSHIP
CANCELLED
```

`PARTIAL_BUDGET` is not an error and should advance only the watermark ranges that were durably completed.

---

## 12. Restricted semantic-prior mutation API

Dream requires a narrow API that cannot mutate learned evidence.

Target interface:

```java
public interface SemanticGraphPriorWriter {

    ApplyResult applyCandidate(
        ChunkGraphNode left,
        ChunkGraphNode right,
        int graphVersion,
        double semanticSimilarity,
        Instant observedAt,
        String semanticPolicyFingerprint,
        long fencingToken,
        int maxSemanticDegree
    );

    RetirementResult retirePrior(
        ChunkGraphNode left,
        ChunkGraphNode right,
        int graphVersion,
        Instant observedAt,
        String semanticPolicyFingerprint,
        long fencingToken,
        RetirementReason reason
    );
}
```

Names may change, semantics may not.

### 12.1 Apply semantics

Within one transaction:

1. validate pair and same ACL;
2. verify current Dream lease/fencing token;
3. lock both published generations and re-check full lifecycle eligibility including TTL;
4. acquire graph node locks in canonical order;
5. enforce semantic degree quota for creation;
6. create or update both graph directions;
7. update semantic-only fields;
8. do not modify online counters;
9. preserve graph version isolation.

### 12.2 Allowed graph-field mutations

Dream may update:

```text
semantic_similarity
semantic_seeded_at (only when first semantic prior is created)
semantic_last_seen_at
compaction_required
updated_at
```

If later schema adds semantic policy provenance to `knowledge_chunk_association`, Dream may update those semantic-only fields as well.

### 12.3 Forbidden graph-field mutations

Dream must never directly modify:

```text
support_count
context_count
citation_count
distinct_query_support
query_support_sketch
last_reinforced_at
weight based on learned evidence
band to WARM/HOT
```

### 12.4 Retirement semantics

Retirement must distinguish:

```text
semantic-only edge
vs
edge with online learned evidence
```

If online learned evidence exists:

```text
clear/degrade semantic fields only
retain pair and learned fields
```

If no online learned evidence exists:

```text
clear semantic prior
mark compaction_required
allow existing maintenance lifecycle to decay/purge the relation
```

Dream SHOULD NOT directly hard-delete association rows in v1.

This avoids a second competing deletion lifecycle.

---

## 13. Source enumeration

Dream v1 has two source lanes.

### 13.1 Fast lane

Enumerate eligible published chunks changed since the durable policy-specific watermark.

Use stable keyset ordering:

```text
(updated_at, access_level, document_id, generation, chunk_id)
```

Do not use OFFSET pagination.

The source repository must return enough information to perform ANN lookup without re-reading authoritative text.

Expected source projection:

```java
record DreamSource(
    ChunkGraphNode node,
    String language,
    float[] embedding,
    Instant updatedAt,
    String embeddingProfileId
) {}
```

### 13.2 Rescan lane

Each run may additionally take a bounded deterministic sample of old eligible nodes.

Goals:

- rediscover neighbourhood changes;
- verify old candidates;
- respond gradually to policy/embedding changes;
- allow eventual semantic forgetting.

The rescan lane must expose estimated full coverage horizon.

It MUST NOT silently switch to full-corpus scanning when the backlog grows.

---

## 14. Candidate discovery algorithm

For each eligible source `A`:

```text
1. consume source/run budget
2. forward ANN topK(A)
3. remove A itself
4. retain only same ACL
5. retain only currently eligible rows
6. enforce same-language policy in v1
7. retain similarity >= candidateThreshold
8. normalize to canonical pairs
9. deduplicate forward pairs
10. obtain reverse topK(B), cached by target node
11. test whether A ∈ topK(B)
12. compute confidence
13. rank accepted candidates for A
14. persist Dream candidate observation
15. at most maxNewEdgesPerChunk may enter semantic activation/apply consideration
```

### 14.1 Reciprocal lookup cache

Reverse neighbourhood results are cached for the active run/batch.

Cache requirements:

- bounded size;
- keyed by full node identity + policy fingerprint;
- no distributed cache in v1;
- eviction is allowed;
- cache contents are non-authoritative and not persisted.

The same `B` discovered from multiple sources should normally require one reverse ANN lookup while present in cache.

### 14.2 Confidence formula v1

```text
similarity = min(forward_similarity, reverse_similarity)
rank_factor = 1 - ((max(forward_rank, reverse_rank) - 1) / topK)
confidence = similarity * (0.85 + 0.15 * rank_factor)
```

Only mutual-KNN pairs can activate in v1.

The formula version string must participate in the semantic policy fingerprint.

### 14.3 Rank consequence

`topK=32` is a discovery window.

With `activationThreshold=0.94`, distant reciprocal neighbours may mathematically be unable to activate even with very high similarity.

This is intentional until shadow calibration proves otherwise.

No implementation should assume ranks `1..32` have equal activation opportunity.

---

## 15. Dream candidate state machine

### 15.1 Discovery/update

A valid observed pair is upserted under the current policy fingerprint.

On mutual positive verification:

```text
positive_streak += 1
negative_streak = 0
last_seen_at = now
last_verified_at = now
```

On negative verification:

```text
negative_streak += 1
positive_streak = 0
last_verified_at = now
```

A failed run that does not successfully verify a candidate MUST NOT count as a negative semantic observation.

### 15.2 Activation

Candidate may transition:

```text
CANDIDATE -> ACTIVE
```

when:

```text
mutual_knn = true
AND confidence >= activationThreshold
AND both nodes currently lifecycle eligible
```

No minimum positive streak is required by default for first semantic activation, because activation itself does not produce WARM/HOT authority. If replay data shows instability, a later policy may add one.

If apply mode is false, candidate state may become Dream `ACTIVE` internally but no graph row/prior is written.

### 15.3 Retention

An ACTIVE Dream candidate remains semantically active while:

```text
mutual_knn = true
AND confidence >= retentionThreshold
```

This provides hysteresis against activation threshold.

### 15.4 Forgetting pressure

Semantic retirement becomes eligible when:

```text
negative_streak >= negativeStreakForForgetting
AND (
    !mutual_knn
    OR confidence < forgettingThreshold
    OR lifecycle invalid
    OR semantic policy obsolete
)
```

Lifecycle invalidation may retire immediately rather than waiting for a semantic negative streak, because an expired/unpublished node is not eligible semantic memory.

### 15.5 State outcomes

```text
CANDIDATE -> ACTIVE
ACTIVE -> STALE
CANDIDATE -> STALE
CANDIDATE/STALE -> REJECTED  // only for deterministic permanent reason under same policy
```

`REJECTED` must not be used for transient ANN misses.

---

## 16. Shadow-mode semantics

With apply disabled, Dream is allowed to mutate only Dream-owned persistence.

Allowed:

```text
knowledge_chunk_dream_candidate
adaptive_graph_dream_run
adaptive_graph_dream_lease
adaptive_graph_dream_checkpoint
Dream metrics
Dream logs/audit
```

Forbidden:

```text
knowledge_chunk_association mutation
online evidence mutation
band mutation
request-time retrieval influence
```

Required integration assertion:

```text
snapshot/hash knowledge_chunk_association
run complete shadow Dream cycle
assert graph snapshot unchanged
```

This test is mandatory before D1 is complete.

---

## 17. Apply-mode semantics

Apply mode does not imply online graph expansion.

Safe rollout combination:

```text
ADAPTIVE_GRAPH_DREAM_ENABLED=true
ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED=true
ADAPTIVE_GRAPH_EXPANSION_ENABLED=false
```

Dream may materialize semantic CANDIDATE priors, but they cannot influence answers through graph expansion.

Online graph influence remains independently gated.

Dream MUST NOT automatically enable maintenance, shadow expansion, expansion or competition.

---

## 18. 4–6 pod ownership model

All pods may instantiate Dream code, but only one active coordinator owns the current Dream lease.

Example:

```text
P1 P2 P3 P4 P5 P6
|  |  |  |  |  |
+--+--+--+--+--+---- lease election
             ↓
           owner P3
```

Non-owner pods are healthy standby candidates.

They do not run duplicate Dream ANN workloads.

### 18.1 Owner identity

Owner ID must uniquely identify one running process instance, not merely a Kubernetes deployment.

Recommended composition:

```text
podName + processStartUuid
```

A restarted pod with the same Kubernetes name is a different owner incarnation.

### 18.2 Lease acquisition

Acquisition is atomic.

A pod may acquire when:

- no lease exists;
- lease is expired;
- it already owns a valid lease and renews it.

Takeover increments fencing token.

### 18.3 Heartbeat

Heartbeat runs independently of ANN/DB worker execution.

Default relationship:

```text
heartbeatInterval <= leaseDuration / 3
```

Heartbeat does not prove worker health forever. The coordinator must also stop heartbeat during controlled shutdown and release/expire ownership.

### 18.4 Lost ownership

If renewal fails or fencing token changes:

```text
mark run LOST_OWNERSHIP
cancel/stop new batches
prevent all further graph mutation
prevent checkpoint advancement
allow in-flight non-authoritative ANN reads to finish/cancel
```

Database apply operations independently validate fencing token, so process-level cancellation is defense in depth rather than the only protection.

---

## 19. Transaction boundaries

Do not wrap an entire Dream batch in one large DB transaction.

### 19.1 Candidate observation transaction

Candidate-store upserts may be grouped in small bounded transactions.

They must be idempotent by canonical identity + policy fingerprint.

### 19.2 Graph apply transaction

Each pair apply/retirement operation or a small safely bounded pair batch must:

- validate lease token;
- validate lifecycle;
- acquire locks canonically;
- mutate both directions consistently;
- commit atomically.

### 19.3 Checkpoint transaction

Checkpoint advancement occurs only after all work represented by that checkpoint is durably persisted.

It must validate the current fencing token.

Crash after graph/candidate commit but before watermark advancement is acceptable: the batch may replay, and operations must be idempotent.

Crash after watermark advancement but before corresponding writes is forbidden by transaction ordering/design.

---

## 20. Locking and concurrency with existing graph writers

Dream shares graph rows with:

- online `AssociationLearningRecorder` / Adaptive Graph reinforcement;
- `IngestionSemanticLinker`;
- `AdaptiveGraphMaintenanceService`;
- semantic compaction/maintenance paths.

Dream semantic apply MUST acquire node locks using the same canonical `ChunkGraphNode` order as semantic seeding/learning paths where available.

The implementation MUST NOT introduce a new reverse lock order.

Before production apply mode, Testcontainers stress testing must execute concurrently:

```text
Dream apply/retire
online reinforcement
semantic ingestion seed
maintenance/compaction
```

Acceptance:

- no lost online counters;
- no asymmetric graph pair caused by Dream;
- no stale-owner mutation;
- no reproducible deadlock under the tested load profile;
- transaction abort/retry rates within agreed budget.

If this test reveals a real lock-protocol conflict, unify the graph lock protocol before enabling Dream apply.

---

## 21. Resource governance

Dream must have its own concurrency semaphores even when sharing the application datasource/executors.

### 21.1 DB

Initial recommendation:

```text
maxDbConcurrency = 2
```

This must be calibrated against actual pool size and 4–6 pod request load.

Dream MUST NOT reserve a majority of JDBC connections.

### 21.2 ANN

Separate controls:

```text
maxForwardAnnConcurrency
maxReverseAnnConcurrency
maxAnnQueriesPerRun
maxReverseAnnQueriesPerRun
```

### 21.3 Wall clock

When `maxRunDuration` is reached:

- finish/rollback current bounded transaction;
- persist completed observations;
- advance only safe checkpoint;
- complete run as `PARTIAL_BUDGET` with stop reason `MAX_RUN_DURATION`.

### 21.4 Backpressure / yield

Dream coordinator should expose an abstraction such as:

```java
DreamAdmissionController.mayContinue()
```

v1 may implement conservative checks using local/JDBC/resource signals already available in AkmAI.

At minimum, batch boundaries must support yielding/sleeping/cancelling without losing checkpoint correctness.

No complex auto-scaler feedback loop is required in v1.

---

## 22. Scheduler behaviour

`AdaptiveGraphDreamScheduler` runs on configured cron/zone on every pod, but scheduler invocation only attempts lease acquisition.

Non-owner behaviour:

```text
attempt lease
not acquired
record standby/skip metric
return
```

Owner behaviour:

```text
acquire lease
start independent heartbeat
create run row
execute bounded fast lane
execute bounded rescan lane
optionally verify/retire existing candidates according to implementation phase
finalize checkpoint
finalize run
stop heartbeat/release or allow normal lease completion
```

Concurrent cron overlap on the same owner must be prevented. A pod already participating in an active run must not start a second run.

---

## 23. Kubernetes lifecycle

On shutdown (`SIGTERM` / Spring context close):

1. stop accepting new Dream batches;
2. stop scheduler starts;
3. finish or rollback short current transaction;
4. prevent further checkpoint advancement after ownership shutdown begins;
5. stop heartbeat;
6. optionally release lease if safe;
7. rely on lease expiry/fencing for crash correctness.

Graceful release is an optimization. Correctness must survive `kill -9`.

Dream failure alone must not make the pod unready for normal RAG serving unless shared infrastructure is actually unhealthy.

---

## 24. Metrics contract

Required metrics, using the project's existing metrics conventions/prefixing:

```text
adaptive_graph_dream_runs_total{phase,outcome}
adaptive_graph_dream_duration_seconds{phase}
adaptive_graph_dream_sources_total{lane}
adaptive_graph_dream_candidates_total{state}
adaptive_graph_dream_mutual_knn_total{result}
adaptive_graph_dream_edges_applied_total{result}
adaptive_graph_dream_edges_retired_total{reason}
adaptive_graph_dream_confidence
aadaptive_graph_dream_degree_rejected_total  // typo forbidden in implementation
adaptive_graph_dream_ann_queries_total{direction}
adaptive_graph_dream_cache_total{result}
adaptive_graph_dream_budget_stops_total{reason}
adaptive_graph_dream_lease_events_total{event}
adaptive_graph_dream_fencing_rejections_total
adaptive_graph_dream_checkpoint_advance_total{result}
adaptive_graph_dream_online_utility_total{stage}
```

The actual metric MUST use correctly spelled:

```text
adaptive_graph_dream_degree_rejected_total
```

### 24.1 Utility cohort

Primary business/quality metric:

```text
dream_candidate_to_online_utility_rate
```

Cohort stages:

```text
created
activated
applied
never_exposed
exposed
admitted
context_selected
cited
grounded_success_contributor
promoted_to_warm_by_online_evidence
promoted_to_hot_by_online_evidence
retired_before_use
```

A structurally richer graph without measurable retrieval utility is not a successful Dream subsystem.

---

## 25. Logging / audit requirements

Do not log raw chunk text merely for Dream diagnostics.

Structured logs should include:

```text
run_id
owner_id
fencing_token
graph_version
semantic_policy_fingerprint
source node identity (when necessary)
candidate canonical identity (when necessary)
reason code
budget stop reason
```

Logs must avoid raw query content because Dream does not need it.

For false-link analysis, persisted provenance in the candidate table is preferred over verbose raw-text logs.

---

## 26. Failure semantics

### ANN/query timeout

- mark operation failure metric;
- do not count as negative semantic verification;
- continue if budget/error policy permits;
- never retire an edge solely because infrastructure timed out.

### Candidate-store write failure

- current batch fails/rolls back as appropriate;
- checkpoint must not advance beyond failed durable work.

### Graph apply failure

- Dream candidate observation may remain recorded;
- graph state must remain atomic;
- apply result records failure;
- checkpoint may advance only according to defined retry/idempotency semantics; safest v1 rule is not to advance the affected source until bounded retry/replay can occur.

### Lease loss

- immediate loss of mutation/checkpoint authority;
- run outcome `LOST_OWNERSHIP`;
- no negative candidate streaks generated merely because work was interrupted.

### Policy change mid-run

A run is bound to the fingerprint captured at start.

If active policy changes during execution, current run must not mix observations across fingerprints. It may finish bounded safe work under its captured policy or stop at batch boundary; the next run uses the new fingerprint.

---

## 27. Class/package plan

Target package:

```text
kz.alimbetov.akmai.knowledge.graph.dream
```

Recommended responsibilities:

```text
AdaptiveGraphDreamScheduler
  cron trigger + lease attempt only

AdaptiveGraphDreamCoordinator
  run orchestration, budgets, phases, cancellation

DreamLeaseManager
  acquire/renew/release/fencing authority

DreamLeaseHeartbeat
  independent lease renewal lifecycle

DreamSourceRepository
  keyset fast lane + deterministic rescan lane

DreamCandidateRepository
  canonical candidate state/provenance persistence

DreamCheckpointRepository
  policy-specific fenced watermark/cursor

DreamRunRepository
  run audit/status/counters

DreamCandidateDiscovery
  forward ANN + dedup + reciprocal verification

DreamReciprocalNeighborVerifier
  reverse ANN + bounded cache

DreamConfidenceCalculator
  explainable v1 formula

DreamPolicyFingerprint
  deterministic canonical SHA-256

DreamCandidateVerification
  cross-time semantic revalidation

DreamSemanticRetirement
  semantic-only retirement decision

SemanticGraphPriorWriter
  restricted fenced graph semantic mutation

DreamBudget
  counters/deadlines

DreamAdmissionController
  online-traffic-first yield decision

DreamRunReport
  immutable run summary
```

Reuse, do not duplicate:

```text
ChunkGraphNode
SemanticNeighborSearchRepository
embedding profile services/storage routing
AppParameterService
AkmaiMetrics
existing lifecycle tables
existing Adaptive Graph band/maintenance logic
```

---

## 28. Migration / code implementation sequence

### DREAM-0 — specification gate

Before code:

- implementation spec approved;
- no unresolved P0 ambiguity;
- branch rebased/synced with `main` before first runtime commit.

### DREAM-1 — configuration and runtime flags

Deliver:

- `AdaptiveGraphProperties.Dream`;
- defaults in `application.yml`;
- startup validation;
- two `AppParameterKey` entries;
- deterministic policy fingerprint;
- unit tests;
- no scheduler side effects while disabled.

Acceptance:

```text
all Dream flags default OFF
invalid config fails startup
fingerprint stable for same config
fingerprint changes for any material semantic-policy change
```

### DREAM-2 — DDL and repositories

Deliver migration `026-adaptive-graph-dream.sql`:

- candidate table;
- run table;
- lease table;
- checkpoint table;
- constraints/indexes;
- changelog include.

Deliver repository tests with PostgreSQL/Testcontainers.

Acceptance:

- canonical duplicate pair cannot be inserted twice concurrently;
- policy fingerprints isolate rows;
- fencing token monotonic;
- checkpoint update rejects stale fencing token.

### DREAM-3 — ownership, heartbeat, resumability

Deliver:

- scheduler;
- lease manager;
- independent heartbeat;
- coordinator cancellation;
- run lifecycle;
- checkpoint logic.

Acceptance with 6 simulated coordinators:

```text
exactly one active owner
healthy long batch retains ownership via heartbeat
owner death permits takeover
stale owner cannot advance checkpoint
stale owner cannot apply semantic prior
rolling restart resumes without source gap
```

### DREAM-4A — lifecycle hardening

Before ANN Dream discovery is called production-safe:

- TTL added to `SemanticNeighborSearchRepository`;
- TTL added to transactional semantic apply eligibility;
- tests for expiry race.

Acceptance:

```text
expired source never discovered
expired target never returned
candidate expiring after discovery is rejected at apply
```

### DREAM-4B — shadow discovery

Deliver:

- source fast lane;
- rescan lane;
- forward ANN;
- canonical dedup;
- reciprocal verifier;
- reverse cache;
- confidence calculation;
- candidate persistence;
- budgets/metrics.

Apply remains disabled.

Acceptance:

- mutual pair accepted;
- one-way neighbour never activates;
- cross ACL impossible;
- same-language v1 policy enforced;
- cache reduces duplicate reverse lookups;
- all resource budgets stop cleanly;
- `knowledge_chunk_association` byte/logical snapshot unchanged by full shadow run.

### DREAM-5 — semantic prior writer

Deliver restricted `SemanticGraphPriorWriter`.

Acceptance:

- applies only CANDIDATE semantic prior;
- never changes online counters;
- respects max semantic degree;
- pair directions remain consistent;
- lifecycle/fencing validated transactionally;
- stale owner rejected.

### DREAM-6 — verification and retirement

Deliver cross-time verification and conservative semantic retirement.

Acceptance:

- positive verification refreshes semantic metadata only;
- ANN infrastructure failure does not create negative streak;
- semantic-only stale relation loses semantic prior after policy threshold;
- learned relation retains learned evidence when semantic prior retired;
- Dream does not hard-delete learned association.

### DREAM-7 — concurrency acceptance

Stress:

```text
6 pods serving RAG traffic
1 Dream owner
online graph learning
IngestionSemanticLinker
AdaptiveGraphMaintenance
Dream apply/retire
```

Acceptance:

- no correctness violation;
- no connection starvation;
- no reproducible deadlock;
- p95/p99 request regression inside agreed budget;
- error-rate regression inside agreed budget.

### DREAM-8 — replay/calibration gate

Use reviewed real corpus.

Measure:

```text
activated-edge precision
false-link rate
reciprocal rate
candidate stability
hub/degree distribution
ANN/DB cost
full-rescan horizon
candidate -> online utility conversion
Recall@K
MRR/nDCG
grounded-answer rate
citation coverage
```

No online influence before this evidence is reviewed.

---

## 29. Rollout states

### R0 — disabled

```text
DREAM_ENABLED=false
DREAM_APPLY_ENABLED=false
```

### R1 — shadow

```text
DREAM_ENABLED=true
DREAM_APPLY_ENABLED=false
```

Allowed to discover/verify/persist Dream state only.

### R2 — apply semantic priors, no graph influence

```text
DREAM_ENABLED=true
DREAM_APPLY_ENABLED=true
ADAPTIVE_GRAPH_EXPANSION_ENABLED=false
```

### R3 — graph shadow evaluation

Use existing graph shadow facilities/replay to measure would-have-contributed candidates.

### R4 — canary online influence

Only after replay GO gate.

### R5 — broader rollout

Only after sustained utility and latency/resource evidence.

Rollback at any stage is disabling Dream apply and/or Dream execution. Existing online learned evidence remains intact.

---

## 30. Required test matrix

### Unit

- configuration boundaries;
- threshold ordering;
- policy fingerprint determinism;
- canonical pair ordering;
- confidence formula monotonicity;
- state transitions/hysteresis;
- negative streak semantics;
- budgets;
- reason-code mapping.

### PostgreSQL/Testcontainers

- migration/constraints/indexes;
- candidate concurrency uniqueness;
- ACL isolation;
- TTL eligibility;
- publication race at apply;
- lease takeover/fencing;
- heartbeat under long work;
- checkpoint stale-token rejection;
- graph pair symmetric semantic apply;
- no online-counter mutation;
- retirement preserves learned evidence;
- node degree race;
- concurrent Dream/learning/maintenance/ingestion seed.

### Multi-pod simulation

- 4 pods;
- 6 pods;
- owner kill;
- rolling restart;
- network/DB pause longer than one heartbeat but shorter than lease where applicable;
- lease expiry and takeover;
- stale owner continuation attempt.

### Performance

Baseline and Dream-on comparison under 4–6 pod serving load:

```text
request p50/p95/p99
throughput
error rate
JDBC wait/usage
ANN latency
Dream run duration
Dream queries/run
CPU/memory
```

### Statistical/replay

Use positive and hard-negative semantic pairs including negation/numeric/legal-condition cases where embeddings may be deceptively close.

---

## 31. Quality gates

Exact numeric thresholds for semantic precision/online quality MUST come from the real corpus, not this document.

However the direction gates are fixed.

Apply-mode GO requires:

```text
no lifecycle/security correctness failures
candidate precision accepted by reviewed corpus
false-link rate within agreed budget
hub distribution bounded
resource cost acceptable
```

Online-influence GO additionally requires:

```text
retrieval recall improves or materially useful cohort conversion is demonstrated
MRR/nDCG non-regressing
grounded-answer rate non-regressing
citation coverage non-regressing
availability/error rate non-regressing
p95/p99 within agreed regression budget
```

If Dream does not show utility, it remains disabled/shadow rather than being enabled because the graph looks richer.

---

## 32. Ambiguities explicitly resolved by this specification

### A-01 — Is Dream a new retrieval strategy?

No. Dream is offline graph-memory consolidation. It does not enter `ResultFusion` as an RRF strategy.

### A-02 — Can Dream create graph rows in shadow mode?

No.

### A-03 — Can Dream candidate state be `ACTIVE` in shadow mode?

Yes, inside `knowledge_chunk_dream_candidate` only. It means semantic activation, not production graph authority.

### A-04 — Does repeated nightly verification increase online evidence?

No.

### A-05 — Can Dream promote WARM/HOT?

No.

### A-06 — Can Dream delete an edge that users proved useful?

Not by semantic retirement. It may clear semantic prior; learned evidence remains governed by normal maintenance.

### A-07 — Does every pod execute Dream work?

Every pod may attempt lease acquisition; only one owner executes the Dream workload in v1.

### A-08 — Does autoscaling 4 -> 6 pods increase Dream parallelism?

No.

### A-09 — Is `@Scheduled` sufficient for ownership?

No. Cluster lease + fencing + heartbeat are mandatory.

### A-10 — Does Dream use all changed chunks every night regardless of budget?

No. Work is budgeted and resumable.

### A-11 — Does policy change cause immediate full reprocessing?

No. It creates a new policy fingerprint and bounded progressive coverage.

### A-12 — Is missing reciprocal ANN result always negative evidence?

Only if the verification completed successfully. Timeout/error/cancellation is not semantic negative evidence.

### A-13 — Does source retirement wait for three negative streaks?

No. Lifecycle invalidity is authoritative and may invalidate/retire the semantic prior immediately.

### A-14 — Is the Dream candidate store the online graph?

No. It is hypothesis/provenance persistence only.

### A-15 — Can Dream mutate `semantic_similarity` downward?

Yes, through the restricted semantic-prior writer/retirement semantics. It must not rely solely on the ingestion monotonic-max seeding API.

### A-16 — Can Dream hard-delete graph associations?

Not in v1. It clears/degrades semantic priors and lets existing graph maintenance own decay/purge.

### A-17 — Is `topK=32` a production constant?

No. It is a shadow calibration default.

### A-18 — Is one Dream coordinator a scalability limitation to solve immediately?

No. First measure run duration/rescan horizon. Sharding is a later optimization only if required by measured capacity.

---

## 33. Definition of Done for Dream v1 implementation

Dream v1 is implementation-complete only when:

1. all Dream flags default OFF;
2. configuration validation is strict;
3. policy fingerprint is deterministic;
4. migration 026 and repositories are green on Testcontainers;
5. candidate pair identity is canonical and concurrency-safe;
6. 4–6 pod election yields exactly one active coordinator;
7. heartbeat protects a healthy owner during long work;
8. stale fencing token cannot mutate graph/checkpoint;
9. lifecycle eligibility includes TTL everywhere required;
10. shadow run provably leaves online graph unchanged;
11. reciprocal ANN discovery is bounded/cached;
12. Dream cannot change online evidence counters;
13. semantic apply is CANDIDATE-only;
14. semantic retirement preserves learned evidence;
15. checkpointing survives crash/replay idempotently;
16. Dream yields/stops safely on resource budgets;
17. concurrent graph-writer stress is green;
18. 4–6 pod serving performance remains inside agreed quality budget;
19. replay/statistical evidence is recorded;
20. production apply/online influence remains separately gated and disabled until GO criteria are explicitly met.

---

## 34. Recommended first implementation PR sequence

Do not implement the entire subsystem in one unreviewable change.

Recommended sequence inside `feature/adaptive-graph-dream` or child implementation commits:

```text
PR/commit group 1
DREAM-1 configuration + flags + fingerprint

PR/commit group 2
DREAM-2 migration 026 + candidate/run/lease/checkpoint repositories

PR/commit group 3
DREAM-3 lease + heartbeat + coordinator + resumability

PR/commit group 4
DREAM-4A existing semantic repository TTL hardening

PR/commit group 5
DREAM-4B shadow reciprocal discovery + budgets + metrics

QUALITY GATE
review shadow evidence

PR/commit group 6
DREAM-5 restricted semantic prior writer

PR/commit group 7
DREAM-6 verification + semantic retirement

PR/commit group 8
DREAM-7 concurrency/performance acceptance

PR/commit group 9
DREAM-8 replay/calibration evidence
```

The first runtime milestone should end after **shadow reciprocal discovery**. This creates measurable evidence before Dream is allowed to mutate production graph memory.

---

## 35. Final implementation principle

The subsystem is intentionally brain-inspired but production-constrained:

```text
association
+ consolidation
+ forgetting
+ offline re-evaluation
```

are useful analogies.

But AkmAI's implementation authority is:

```text
semantic plausibility != factual truth
semantic repetition != user utility
offline confidence != learned evidence
```

Therefore the v1 contract remains:

> Dream may propose, verify and retire semantic hypotheses. Only grounded online evidence may prove retrieval utility and promote learned graph memory.
