# Query Memory and Learning Events

Status: **DRAFT / IMPLEMENTED — exact-head verification pending**

This document defines the current runtime contract for persistent Query Memory, RAG learning events and explicit feedback. It describes implemented behavior; it does not authorize enabling self-optimizing features without release evidence.

## Purpose

AkmAI may retain bounded retrieval observations so later retrieval policy evaluation and query expansion can learn from previously grounded executions. Learning data is an optimization signal, never authoritative knowledge.

The core rule is:

```text
authoritative knowledge
  !=
query memory / learning event / feedback
```

Historical memory may influence retrieval only through explicitly enabled consumers and only after current ACL/lifecycle validation.

## Runtime components

- `SemanticQueryMemory` — bounded in-process cache plus optional PostgreSQL-backed memory.
- `SemanticQueryMemoryRepository` — PostgreSQL source of truth for persistent clusters and observations.
- `SemanticQueryMemoryNamespaceResolver` — resolves the active embedding/retrieval/learning/grounding namespace.
- `QueryMemorySourceEligibility` — revalidates every historical source reference immediately before reuse.
- `RagLearningRecorder` — records privacy-safe execution evidence when the relevant feature gate is enabled.
- `RagLearningEventRepository` — persistent request-level learning event store.
- `RagFeedbackRepository` / `RagFeedbackService` — idempotent explicit-feedback persistence and trust classification.

## Feature gates

Repository defaults keep persistent learning disabled. The important gates are independent:

```text
learningEventsEnabled
persistentQueryMemoryEnabled
executionObservationsEnabled
```

Disabled learning must not change answer correctness or authoritative retrieval semantics.

## Query Memory process

```text
grounded answer
  -> derive cited source refs
  -> normalize ACL scope
  -> resolve active namespace
  -> privacy fingerprint query
  -> update bounded local cluster
  -> optional transactional PostgreSQL persist

later query
  -> resolve same active namespace
  -> refresh bounded persisted clusters
  -> similarity match inside caller ACL scope
  -> load bounded observations
  -> QueryMemorySourceEligibility
  -> only currently online source-backed observations may reach HyDE/query hints
```

## Namespace isolation

Persistent Query Memory is keyed by the full semantic/runtime namespace:

```text
embedding_profile_id
retrieval_policy_version
learning_policy_version
grounding_policy_version
```

A cluster created under one namespace MUST NOT be reused by another namespace. A `cluster_id` cannot be rebound to a different namespace.

Required caller access levels are stored with the cluster. A memory match is eligible only when the current caller scope contains the complete required scope.

Legacy/unscoped rows are not implicitly promoted into a current policy namespace.

## Source lifecycle eligibility

Every source reference reused from historical Query Memory must still resolve to a current search projection satisfying the canonical published lifecycle contract:

```text
lifecycle_status = READY
retention_status = ACTIVE
published_generation = projection.generation
generation_status = PUBLISHED
expires_at IS NULL OR expires_at > PostgreSQL clock_timestamp()
caller ACL contains projection.access_level
```

The entire source set of an observation is conjunctive: if one cited source is malformed, missing, expired, superseded, non-`READY`, non-`PUBLISHED` or outside caller ACL, that observation is discarded.

A memory match with no surviving observations is discarded.

This is deliberately fail-closed. Historical memory cannot resurrect retired or expired evidence.

## Persistence and transaction semantics

`SemanticQueryMemoryRepository.persist(...)` executes cluster/observation mutation and bounded cleanup inside one Spring `TransactionTemplate` transaction.

Persistence uses database constraints and conflict handling for anti-poisoning/idempotency:

- cluster creation is `ON CONFLICT (cluster_id) DO NOTHING`;
- an existing cluster is accepted only if its full namespace still matches;
- observation uniqueness is `(cluster_id, query_fingerprint)`;
- duplicate query fingerprints do not create additional support observations;
- duplicate attempts advance the refresh revision so replicas can observe current state without increasing support;
- observations per cluster and clusters per namespace are bounded;
- expired clusters are removed by namespace and cutoff.

Persistent state is an optimization. Failure to produce a valid memory hint must degrade to no memory hint, not to an unsafe answer.

## Learning-event process

`RagLearningRecorder` records request-level learning evidence only when the relevant feature gate is enabled.

The persisted event contains, among other bounded metadata:

- request ID;
- privacy-safe query fingerprint rather than raw query text;
- ACL scope;
- language/query class;
- corpus and embedding profile attribution;
- retrieval/learning/grounding policy versions;
- answer and grounding status;
- retrieved/selected/cited counts;
- total latency;
- bounded execution trace / source fingerprint attribution.

`RagLearningEventRepository.save(...)` uses `request_id` as the idempotency boundary (`ON CONFLICT (request_id) DO NOTHING`). Replayed recorder calls therefore cannot multiply one request into several training events.

Recorder failures are deliberately non-fatal to the user request. Learning is observational and must not become an availability dependency for answering.

## Training eligibility

Router-training reads are conservative. Training samples require grounded/supported answers with citations and exclude degraded or critical-failure retrieval executions. Samples with `ANALYSIS_UNAVAILABLE` query class are excluded.

Training queries are deduplicated by query fingerprint/query class before bounded selection. Source-fingerprint attribution is retained when available so policy evidence can distinguish legacy/unknown source lineage from explicitly attributed evidence.

## Feedback and anti-poisoning

Explicit feedback is tied to an existing request/learning event and is subject to idempotency and trust classification.

Rules:

1. one request cannot accumulate repeated reinforcement by rotating idempotency keys;
2. replaying the same feedback is idempotent;
3. attempting to attach a contradictory feedback payload to an already-recorded request fails rather than silently overwriting the signal;
4. trust class is stored with the feedback and must be respected by downstream learning policy;
5. feedback is evidence about retrieval/generation utility, not authority to mutate source knowledge.

## Positive cases

### QML-P01 — persisted memory survives process-local cache loss

A cluster and bounded observations persisted in PostgreSQL can be reloaded by namespace and revision.

### QML-P02 — same-policy memory reuse

A query may receive historical observations only when embedding/retrieval/learning/grounding namespace and caller ACL scope are compatible.

### QML-P03 — independent support

Distinct query fingerprints may contribute independent support to the same cluster, subject to configured observation bounds.

### QML-P04 — replica refresh

A later refresh revision is discoverable with `findClustersUpdatedAfter(...)`, allowing another process to incrementally refresh its local cache.

### QML-P05 — idempotent learning/feedback

Replayed request learning events or feedback do not multiply one user interaction into multiple support signals.

## Negative cases

### QML-N01 — expired source

An otherwise stored observation whose source document has expired is not reusable.

### QML-N02 — superseded generation

An observation citing a projection from an older generation is discarded after publication moves to a newer generation.

### QML-N03 — ACL mismatch

A source outside the caller's access-level set cannot enter memory-assisted query generation.

### QML-N04 — non-ready lifecycle

A historical source whose lifecycle leaves `READY` is discarded even if its projection row still exists physically.

### QML-N05 — malformed or partial source set

Malformed source references, missing projections or a mixed source set containing one invalid source cause that observation to be rejected.

### QML-N06 — cross-policy reuse

Clusters and observations from another retrieval/learning/grounding namespace are invisible.

### QML-N07 — duplicate reinforcement

The same query fingerprint cannot increase support repeatedly for one cluster, and one request cannot receive multiple feedback reinforcements through different idempotency keys.

### QML-N08 — learning failure

A learning-store/runtime failure must not fail the primary answer path; the learning signal is dropped/logged instead.

## Concurrency and recovery

PostgreSQL uniqueness constraints are the final idempotency authority for request learning events, feedback and query-memory observation fingerprints. Local caches are acceleration only and are not distributed authority.

Replica convergence uses monotonic `refresh_revision` for persisted Query Memory. No JVM clock is used as a distributed lifecycle/fencing authority for source eligibility; source validity is re-read through the canonical PostgreSQL lifecycle predicate.

If a persisted cluster expires or its underlying sources become ineligible, physical memory cleanup may lag, but runtime source revalidation prevents stale evidence from being reused meanwhile.

## Executable evidence

Current tests include:

- `QueryMemoryAntiPoisoningIntegrationTest`
  - duplicate fingerprint suppression;
  - independent fingerprint support;
  - policy namespace isolation;
  - cluster namespace rebinding rejection;
  - legacy namespace isolation.
- `QueryMemorySourceEligibilityIntegrationTest`
  - live source accepted;
  - expired source rejected;
  - conjunctive mixed-source rejection;
  - malformed source rejection;
  - ACL mismatch rejection;
  - superseded-generation rejection;
  - non-`READY` rejection;
  - empty caller scope fail-closed.
- `SelfOptimizingRagPersistenceIntegrationTest`
  - PostgreSQL round-trip;
  - revision-based replica refresh;
  - TTL cleanup;
  - learning-event ACL persistence;
  - feedback idempotency;
  - policy promotion evidence gates.
- `RagFeedbackAntiPoisoningIntegrationTest`
  - one feedback signal per request despite different idempotency keys;
  - contradictory replay rejection;
  - trust-class persistence.
- `HydeQueryGeneratorTest`
  - query-memory hints are an optional bounded input and source eligibility is invoked before reuse.

## Operational rules

- Keep persistent Query Memory and learning events disabled until deployment-specific evidence justifies enabling them.
- Do not bypass `QueryMemorySourceEligibility` for prompt construction or query expansion.
- Do not use raw user query text as the durable learning identity when a privacy fingerprint is available.
- Do not treat query-memory support count as source authority.
- Any future training consumer must document exact sample eligibility, trust weighting, poisoning limits and rollback/promotion gates before production enablement.

## Verification status

The runtime and integration evidence above are implemented on `quality/production-readiness-hardening-v1`. This contract remains `DRAFT` until the same exact branch head passes the required CI/quality/storage/image gate set. After that exact-head proof, the service inventory may promote this process to `VERIFIED` without changing runtime semantics.
