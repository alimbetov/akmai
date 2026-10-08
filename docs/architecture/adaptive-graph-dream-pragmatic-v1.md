# Adaptive Graph Dream — pragmatic v1 profile

Classification: **PROPOSED / NORMATIVE FOR DREAM V1 IMPLEMENTATION**  
Target branch: `feature/adaptive-graph-dream`  
Date: 2026-10-08

This document is a pragmatic implementation profile for `adaptive-graph-dream-cycle.md`.

The long-form Dream specification describes the full design space. This profile defines the minimum production-safe version that may be implemented first. Where this document is stricter than the long-form design, this document wins for Dream v1.

---

## 1. Product intent

Adaptive Graph Dream is not an attempt to simulate a human brain literally. It borrows a small set of useful mechanisms from associative memory:

```text
experience
  -> association
  -> consolidation
  -> competition
  -> recall
  -> forgetting
  -> offline re-evaluation
```

AkmAI remains a deterministic, auditable RAG system. Brain-inspired behaviour is allowed only inside hard production boundaries:

```text
ACL
published generation
READY lifecycle
ACTIVE retention
TTL eligibility
embedding/profile compatibility
bounded degree
bounded runtime cost
fencing
citation / grounding authority
```

The pragmatic Dream objective is therefore:

> Discover plausible semantic associations offline, measure whether they later improve retrieval, and never confuse semantic plausibility with proven utility.

---

## 2. Core authority rule

Dream can create a hypothesis. Dream cannot prove the hypothesis useful.

```text
embedding similarity
        ↓
Dream candidate
        ↓
reciprocal neighbourhood verification
        ↓
semantic confidence
        ↓
optional CANDIDATE graph prior
        ↓
real grounded online usage
        ↓
WARM
        ↓
repeated useful grounded usage
        ↓
HOT
```

Dream MUST NOT increment or fabricate any online-evidence field, including:

- `support_count` when it represents user-derived reinforcement;
- `context_count`;
- `citation_count`;
- `distinct_query_support`;
- query-support sketches;
- online reinforcement timestamps;
- any future counter whose semantics mean successful request-time use.

Dream MUST NOT directly promote an association to `WARM` or `HOT`.

This rule is architectural, not merely conventional. Dream components should not depend on a repository API that exposes general online reinforcement or band-promotion operations.

---

## 3. Pragmatic subsystem boundaries

Use `Dream` as the subsystem name, but prefer technical names for implementation responsibilities:

```text
AdaptiveGraphDreamCoordinator
DreamCandidateDiscovery
DreamCandidateVerification
DreamSemanticRetirement
DreamCandidateRepository
DreamLeaseManager
DreamWatermarkRepository
DreamPolicyFingerprint
```

Avoid using the word `Reinforcement` for semantic re-validation. In AkmAI, reinforcement should continue to mean evidence derived from real online use.

Dream v1 has three responsibilities:

1. **Discovery** — propose reciprocal high-confidence semantic candidates.
2. **Verification** — re-check existing Dream candidates under the current semantic policy.
3. **Semantic retirement** — remove or weaken semantic-only priors that are no longer supported.

It does not own online graph lifecycle promotion.

---

## 4. P0 lifecycle eligibility invariant

Every Dream read and every Dream apply operation MUST use the same canonical knowledge eligibility rule:

```text
lifecycle_status = READY
AND retention_status = ACTIVE
AND published_generation = node.generation
AND (expires_at IS NULL OR expires_at > clock_timestamp())
```

This is required at two points:

```text
ANN discovery time
        ↓
Dream candidate
        ↓
transactional apply time
```

The second check is mandatory because publication, retention or TTL may change after discovery and before apply.

Dream MUST NOT rely on final request-path revalidation as a substitute for this rule.

### Required existing-repository hardening

Before `DREAM-4` is considered complete:

- `SemanticNeighborSearchRepository` must exclude expired lifecycle rows;
- `SemanticAssociationSeedRepository` must reject expired lifecycle rows inside the apply transaction;
- the eligibility predicate should preferably be represented by a shared repository/helper boundary to reduce semantic drift.

This is a release blocker for Dream apply mode.

---

## 5. P0 semantic-prior degradation API

The current semantic seed path is naturally monotonic: it preserves the greatest observed semantic similarity. That behaviour is appropriate for ingestion seeding but insufficient for Dream retirement.

Dream requires a separate mutation boundary for semantic evidence.

Conceptually:

```text
SemanticPriorWriter
  updateSemanticPrior(...)
  clearSemanticPrior(...)
```

This API MAY update only semantic state such as:

```text
semantic_similarity
semantic_last_seen_at
semantic policy/provenance
semantic state
compaction_required
updated_at
```

It MUST NOT alter:

```text
support_count
context_count
citation_count
distinct_query_support
query_support_sketch
last_reinforced_at
online band evidence
```

Required behaviour:

```text
semantic evidence disappears
+ online learned evidence exists
        ↓
clear/degrade semantic prior
retain learned edge and learned counters
```

For a semantic-only association with no online evidence, repeated negative verification may eventually make the graph association eligible for decay/removal through the existing graph lifecycle.

Dream must never delete proven learned evidence solely because embedding similarity changed.

---

## 6. Candidate discovery algorithm

Dream v1 uses reciprocal semantic neighbourhoods.

For a source node `A`:

```text
1. read current active embedding
2. ANN top-K(A)
3. reject self
4. enforce same ACL
5. enforce lifecycle eligibility
6. enforce language policy
7. reject below candidate threshold
8. canonical-pair deduplicate
9. verify A ∈ top-K(B)
10. compute semantic confidence
11. retain at most configured new edges per source
```

Initial calibration defaults remain:

```text
top_k = 32
candidate_threshold = 0.88
activation_threshold = 0.94
retention_threshold = 0.90
forgetting_threshold = 0.86
max_new_edges_per_chunk = 3
```

These are shadow calibration defaults, not production truth.

---

## 7. Confidence semantics

The v1 confidence formula remains intentionally explainable:

```text
similarity = min(forward_similarity, reverse_similarity)
rank_factor = 1 - ((max(forward_rank, reverse_rank) - 1) / K)
confidence = similarity * (0.85 + 0.15 * rank_factor)
```

Important operational consequence:

With `K=32` and `activation_threshold=0.94`, distant reciprocal neighbours are intentionally very difficult or impossible to activate. `top-K=32` is therefore a discovery window, not an assertion that every rank 1..32 has a realistic activation path.

Do not tune this formula from intuition. Shadow mode must collect:

- forward/reverse rank distributions;
- confidence distributions;
- reciprocal acceptance rate;
- later online-useful conversion.

Threshold changes require corpus evidence.

---

## 8. ANN cost model and bounded reverse verification

The system must not describe Dream cost as merely `O(changed_chunks * K)` without accounting for reciprocal lookup cost.

A naive source may require:

```text
1 forward ANN
+ up to K reverse ANN lookups
```

With `K=32` and `10,000` sources, the theoretical ceiling is large enough to affect database/vector capacity.

Dream v1 therefore MUST:

1. canonical-deduplicate forward candidates before reverse lookup;
2. perform at most one reverse `top-K(B)` lookup per unique target node in a batch/run;
3. use a bounded reverse-neighbour cache;
4. enforce hard run budgets.

Required first-class budgets:

```text
max_sources_per_run
max_ann_queries_per_run
max_reverse_ann_queries_per_run
max_db_rows_touched_per_run
max_wall_clock_duration
batch_size
query_timeout
transaction_timeout
```

When a budget is exhausted, the run must stop cleanly without corrupting the watermark.

Interactive retrieval traffic has priority over Dream work.

---

## 9. Source lanes and eventual semantic refresh

Changed-source processing alone is insufficient for long-term verification and forgetting.

Dream v1 therefore has two bounded lanes:

### Fast lane

```text
new/changed published nodes since watermark
```

Executed each Dream run.

### Rescan lane

```text
bounded deterministic sample of older eligible nodes
```

Used to detect semantic changes that are not accompanied by a source-row update, including neighbourhood reshaping or semantic-policy changes.

Expose:

```text
dream_rescan_nodes_per_run
dream_estimated_full_rescan_horizon
```

The rescan lane must remain bounded; Dream must never fall back to unbounded full-corpus all-pairs work.

---

## 10. Canonical Dream candidate store

Dream state remains separate from `knowledge_chunk_association`.

Canonical table:

```text
knowledge_chunk_dream_candidate
```

One row represents one undirected canonical pair.

Minimum identity:

```text
access_level
node_a_document_id
node_a_generation
node_a_chunk_id
node_b_document_id
node_b_generation
node_b_chunk_id
graph_version
semantic_policy_version
semantic_policy_fingerprint
```

Minimum semantic state:

```text
state  // CANDIDATE | ACTIVE | STALE | REJECTED
forward_similarity
reverse_similarity
forward_rank
reverse_rank
mutual_knn
confidence
positive_streak
negative_streak
first_seen_at
last_seen_at
last_verified_at
activated_at
updated_at
```

Minimum provenance:

```text
embedding_profile_id
discovery_run_id
last_verified_run_id
discovery_reason
activation_reason
retirement_reason
```

The purpose is operational explainability. For any Dream relation an operator should be able to answer:

```text
Why was this relation proposed?
Under which model/policy?
At which ranks/similarities?
Why was it activated or retired?
```

---

## 11. Semantic policy fingerprint

Store both a human-readable policy version and a deterministic fingerprint.

Example:

```text
semantic_policy_version = dream-v1
semantic_policy_fingerprint = sha256(canonical_policy_json)
```

The fingerprint MUST include all material semantic-policy inputs, at minimum:

```text
embedding model/profile
embedding dimensions
distance semantics
language policy
top-K
candidate threshold
confidence formula version
normalization semantics
```

If a material policy input changes, old Dream observations must not silently be treated as current even if an operator forgot to increment a manual version number.

---

## 12. Shadow mode contract

Configuration:

```text
ADAPTIVE_GRAPH_DREAM_ENABLED=true
ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED=false
```

means **shadow discovery/verification**, not total read-only execution.

Allowed shadow mutations:

```text
knowledge_chunk_dream_candidate
Dream run metadata
Dream observations/provenance
watermark/checkpoint
lease state
metrics
```

Forbidden shadow mutations:

```text
create knowledge_chunk_association row
modify semantic prior on graph association
modify online evidence counters
modify graph band
influence request-time ranking/context
```

A regression test must prove that a full shadow Dream run leaves `knowledge_chunk_association` unchanged.

---

## 13. Lease and fencing

Dream is multi-replica unsafe without cluster ownership.

Use the established lease/fencing model:

```text
owner_id
lease_until
fencing_token
```

But renewal only between batches is not sufficient. Dream v1 MUST use an independent heartbeat because a single ANN/database batch may exceed the lease duration.

Conceptually:

```text
Dream coordinator
   ├── worker: discovery / verification / persistence
   └── heartbeat: renew approximately every lease_duration / 3
```

On authority loss:

```text
stop apply operations
stop watermark advancement
abort remaining work safely
allow takeover
```

All apply operations and watermark advancement must be fenced.

---

## 14. Degree and hub control

Dream creation budget is intentionally stricter than the graph's total lifecycle quota.

Default:

```text
max_new_edges_per_chunk = 3
```

Dream v1 must enforce:

- mutual-KNN for activation;
- canonical pair uniqueness;
- per-source creation budget;
- semantic degree limit;
- no multi-hop Dream expansion;
- no automatic cross-language Dream;
- no automatic ontology mutation.

Generic-chunk suppression may be added only after measured false-positive/hub evidence. Do not introduce broad hard-coded text heuristics in the first implementation.

---

## 15. Dream retirement semantics

Semantic retirement is conservative.

Possible negative evidence:

```text
no longer mutual-KNN
similarity below retention/forgetting threshold
node no longer lifecycle eligible
generation obsolete
semantic policy incompatible
repeated verification miss
```

A single approximate ANN miss is not sufficient.

Default rule:

```text
negative_streak >= 3
AND confidence < forgetting_threshold
```

Then:

### If online learned evidence exists

```text
remove/degrade semantic prior
retain learned association
let normal graph maintenance decide learned lifecycle
```

### If no online learned evidence exists

```text
semantic candidate -> STALE
semantic prior may be cleared
graph semantic-only association may become eligible for existing decay/purge rules
```

Dream must not create a second competing graph lifecycle state machine.

---

## 16. North-star utility metric

The success criterion is not how many semantic edges Dream can discover.

Primary metric:

```text
dream_candidate_to_online_utility_rate
```

Track candidate cohorts through:

```text
Dream candidate created
    ↓
semantic candidate activated
    ↓
graph edge eventually exposed online
    ↓
admitted
    ↓
survived context selection
    ↓
cited / contributed to grounded success
    ↓
promoted by REAL online evidence
```

Required cohort counters/rates:

```text
created
activated
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

Secondary metrics remain useful:

- reciprocal-KNN rate;
- false-link rate from reviewed corpus;
- degree/hub distribution;
- candidate stability;
- ANN and DB cost;
- p50/p95/p99 Dream batch duration;
- retrieval recall delta;
- MRR/nDCG delta;
- grounding/citation delta.

A graph that becomes structurally richer but does not improve retrieval utility is not a successful Dream system.

---

## 17. Pragmatic rollout

### D0 — disabled

```text
dream-enabled=false
dream.apply-enabled=false
```

No Dream execution.

### D1 — shadow discovery

```text
dream-enabled=true
dream.apply-enabled=false
```

Implement configuration, candidate store, lease/fencing, watermark, discovery, reciprocal verification and telemetry.

No graph mutation.

### D2 — calibration gate

Before graph apply is allowed, require measured evidence for:

```text
candidate precision >= agreed target
false-link rate <= agreed budget
hub/degree distribution acceptable
ANN/DB/wall-clock cost acceptable
candidate stability acceptable
lifecycle/ACL/fencing tests green
```

Threshold values must come from the real evaluation corpus rather than being invented in code.

### D3 — candidate apply, online graph influence still disabled

Enable semantic prior creation only after D2.

```text
dream.apply-enabled=true
adaptive graph online expansion=false
```

Dream may create semantic CANDIDATE priors through the restricted semantic-prior boundary.

### D4 — semantic verification/retirement

Add cross-time verification and retirement only after candidate creation semantics are proven stable.

### D5 — graph shadow evaluation

Run adaptive graph shadow expansion/replay and measure utility without affecting production answers.

Required GO signals:

```text
retrieval recall delta > 0
MRR/nDCG non-regressing
grounded answer rate non-regressing
citation coverage non-regressing
false-positive budget acceptable
resource cost acceptable
```

### D6 — canary online influence

Only after D5 may a small canary allow Dream-derived graph candidates to influence online retrieval.

### D7 — broader rollout

Broader enablement requires sustained evidence from online utility cohorts.

Never enable Dream apply and uncalibrated online graph expansion together on an empty deployment.

---

## 18. DREAM-1 .. DREAM-4 implementation gate

The first implementation phase is complete only when all items below are true.

### DREAM-1 — configuration/runtime contract

- Dream flags default OFF;
- shadow/apply semantics are explicit;
- threshold and budget validation fails fast;
- semantic policy fingerprint is deterministic;
- no request-path behaviour changes.

### DREAM-2 — persistence

- canonical candidate table exists;
- pair ordering is deterministic;
- uniqueness is concurrency-safe;
- provenance is persisted;
- checkpoint/watermark state is transactional and resumable.

### DREAM-3 — ownership

- one cluster owner at a time;
- independent heartbeat;
- fencing token required for apply/checkpoint advancement;
- stale owner cannot mutate after takeover;
- lease-loss test is green.

### DREAM-4 — discovery + reciprocal verification

- discovery and apply use full READY/ACTIVE/published/unexpired eligibility;
- same-ACL invariant is enforced;
- language policy is explicit;
- forward candidates are canonical-deduplicated;
- reverse ANN lookup is cached/deduplicated;
- ANN/DB/wall-clock budgets are enforced;
- `apply=false` leaves graph association rows unchanged;
- `apply=true` can create only semantic CANDIDATE priors;
- Dream cannot modify online evidence counters or promote bands;
- Testcontainers lifecycle/TTL/ACL/concurrency/fencing tests are green.

Do not begin production online ranking changes as part of DREAM-1..4.

---

## 19. Deliberate non-goals for Dream v1

Do not implement yet:

```text
LLM/NLI verification
multi-hop Dream traversal
context-conditioned graph edges
cross-language automatic Dream links
automatic threshold adaptation
Dream based on Query Memory
Dream-to-Dream utility reinforcement
automatic ontology mutation
biological plasticity simulation
request-time ranking changes
```

These may be researched only after the reciprocal semantic consolidation mechanism demonstrates measurable utility.

---

## 20. Decision rule

The minimal research question for Dream v1 is:

> Do high-precision reciprocal semantic associations discovered offline later become useful retrieval evidence often enough to justify their complexity and resource cost?

If the answer is no, Dream stays a research/shadow subsystem and should not become production graph machinery.

If the answer is yes, the measured utility data — not the brain analogy alone — determines the next level of sophistication.

This keeps the design brain-inspired but production-governed:

```text
plastic semantic memory
        +
strict evidence authority
        +
bounded resource use
        +
auditability
        =
pragmatic Adaptive Graph Dream
```
