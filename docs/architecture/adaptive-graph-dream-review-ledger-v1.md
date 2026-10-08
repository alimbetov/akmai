# Adaptive Graph Dream — design review ledger v1

Classification: **CURRENT REVIEW LEDGER FOR `feature/adaptive-graph-dream`**  
Baseline: `main@381b9c6cbf835daf2b301280171c049596e430a3`  
Date: 2026-10-08

This ledger records defects, ambiguities and implementation risks found while reviewing the Dream design against the current AkmAI runtime.

Normative implementation details live in `adaptive-graph-dream-implementation-spec-v1.md`.

---

## 1. Review verdict

Status: **GO FOR SHADOW IMPLEMENTATION WITH CONTROLLED GATES**.

Allowed next implementation scope:

```text
DREAM-1 configuration/runtime flags/policy fingerprint
DREAM-2 DDL + repositories
DREAM-3 lease/fencing/heartbeat/checkpoint
DREAM-4A semantic lifecycle TTL hardening
DREAM-4B shadow reciprocal ANN discovery
```

Not yet approved for production influence:

```text
Dream graph apply in production
semantic retirement in production
online graph expansion based on Dream-derived priors
multi-owner distributed Dream
```

Those require evidence gates defined in the implementation spec.

---

## 2. Resolved P0/P1 specification defects

### DR-01 — expired knowledge could participate in Dream

Severity: **P0**  
Status: **RESOLVED IN SPEC; CODE CHANGE REQUIRED**

Problem:

Existing semantic neighbour and seed repositories enforce READY/ACTIVE/published generation but the reviewed SQL does not directly include `expires_at` eligibility.

Resolution:

Canonical Dream lifecycle predicate now requires:

```text
READY
ACTIVE
current published generation
unexpired TTL
```

at discovery and transactional apply.

Implementation gate:

`SemanticNeighborSearchRepository` and semantic apply locking must be hardened before DREAM-4/apply acceptance.

---

### DR-02 — ingestion semantic seeding is monotonic and cannot model forgetting

Severity: **P0**  
Status: **RESOLVED IN SPEC; CODE API REQUIRED**

Problem:

The existing seed path retains greatest observed `semantic_similarity`, which is appropriate for ingestion but cannot degrade a semantic prior when Dream observes a weaker/currently invalid relationship.

Resolution:

Introduce restricted `SemanticGraphPriorWriter` with explicit apply/retire semantics.

It may modify semantic fields only and cannot modify learned counters.

Dream does not hard-delete graph relations in v1.

---

### DR-03 — `Dream Reinforcement` terminology conflicted with online reinforcement

Severity: **P1 design ambiguity**  
Status: **RESOLVED**

Resolution:

Implementation terminology:

```text
Discovery
Verification
Semantic Retirement
```

`reinforcement` remains reserved for real online evidence.

---

### DR-04 — 4–6 pods could multiply Dream load

Severity: **P0 availability/performance**  
Status: **RESOLVED**

Resolution:

v1 uses:

```text
4–6 serving pods
1 active Dream coordinator
remaining pods standby
```

Pod autoscaling does not change Dream parallelism.

Future sharding requires separate measured capacity justification.

---

### DR-05 — batch-only lease renewal could lose a healthy owner

Severity: **P0 HA**  
Status: **RESOLVED**

Resolution:

Independent heartbeat is mandatory.

Graph mutation and checkpoint advancement independently verify fencing token.

---

### DR-06 — shadow mode mutation semantics were unclear

Severity: **P1 correctness/auditability**  
Status: **RESOLVED**

Resolution:

Shadow may write only Dream-owned candidate/run/lease/checkpoint state and telemetry.

Shadow may not mutate `knowledge_chunk_association`.

Mandatory integration test snapshots graph before/after a full shadow cycle.

---

### DR-07 — reciprocal ANN cost understated

Severity: **P1 performance**  
Status: **RESOLVED**

Problem:

One forward lookup can generate up to K reverse lookups.

Resolution:

Explicit run budgets + canonical dedup + bounded reverse-neighbour cache + one reverse query per unique cached target.

Dream is spare-capacity work and yields to online load.

---

### DR-08 — policy version alone could be changed incorrectly/manual drift

Severity: **P1 correctness/reproducibility**  
Status: **RESOLVED**

Resolution:

Persist human-readable `semantic_policy_version` plus deterministic SHA-256 `semantic_policy_fingerprint` over material semantic policy inputs.

Candidate uniqueness and compatibility use fingerprint.

---

### DR-09 — changed-source watermark alone cannot support eventual forgetting

Severity: **P1 lifecycle**  
Status: **RESOLVED**

Resolution:

Two lanes:

```text
fast lane = changed/new nodes
rescan lane = bounded deterministic old-node coverage
```

Expose estimated full-rescan horizon.

Never fall back to unbounded full-corpus scan.

---

### DR-10 — missing ANN result could be confused with negative semantic evidence

Severity: **P0 data quality**  
Status: **RESOLVED**

Resolution:

Timeout, cancellation, DB error, ownership loss or partial run is **UNKNOWN**, not negative evidence.

Negative streak increases only after successful semantic verification produces a genuine negative result.

---

### DR-11 — lifecycle invalidation vs negative streak unclear

Severity: **P1 correctness**  
Status: **RESOLVED**

Resolution:

Expired/unpublished/obsolete generation is authoritative invalidation and may retire semantic prior immediately.

The three-negative-streak rule is for semantic uncertainty such as mutual-KNN/similarity degradation, not authoritative lifecycle invalidity.

---

### DR-12 — Dream ACTIVE vs graph ACTIVE/WARM/HOT naming confusion

Severity: **P1 terminology**  
Status: **RESOLVED**

Resolution:

Dream candidate `ACTIVE` means only "semantically activated candidate".

It grants no online graph authority.

Adaptive Graph bands remain `CANDIDATE/WARM/HOT/DECAYED`.

---

### DR-13 — graph mutation authority too broad

Severity: **P0 safety**  
Status: **RESOLVED**

Resolution:

Dream should depend on a restricted semantic writer, not on a general reinforcement/band-mutation repository interface.

Least-authority boundary is part of design.

---

### DR-14 — checkpoint ordering under crash was ambiguous

Severity: **P0 resumability/data loss**  
Status: **RESOLVED**

Resolution:

Durable writes happen before corresponding checkpoint advancement.

Replay due to crash-before-checkpoint is acceptable and operations must be idempotent.

Checkpoint-before-write is forbidden.

---

### DR-15 — concurrent Dream and existing graph writers not explicitly gated

Severity: **P1 concurrency**  
Status: **RESOLVED AS ACCEPTANCE REQUIREMENT**

Resolution:

Testcontainers stress suite must run Dream semantic mutation concurrently with:

```text
online association learning
IngestionSemanticLinker
AdaptiveGraphMaintenance
compaction/maintenance paths
```

No speculative graph-wide lock rewrite is mandated before evidence; a reproducible conflict becomes a required fix before apply rollout.

---

### DR-16 — migration target unspecified

Severity: **P1 implementation ambiguity**  
Status: **RESOLVED FOR CURRENT BASELINE**

Resolution:

Current reviewed baseline ends at migration `025`; Dream target is `026-adaptive-graph-dream.sql`.

If `main` gains another migration before the first Dream DDL commit is rebased, implementation MUST renumber to the next available migration rather than create duplicate numbering.

---

## 3. Intentional calibration decisions — not defects

The following values are hypotheses for shadow mode and are deliberately not declared production truth:

```text
topK = 32
candidateThreshold = 0.88
activationThreshold = 0.94
retentionThreshold = 0.90
forgettingThreshold = 0.86
maxNewEdgesPerChunk = 3
negativeStreakForForgetting = 3
```

They require real-corpus distribution/replay evidence.

Do not open implementation defects merely because these numbers are not yet empirically final.

A defect exists only if code hard-codes them outside configuration/policy fingerprint or enables production apply without the calibration gate.

---

## 4. Explicitly deferred decisions

### DD-01 — LLM/NLI contradiction verification

Deferred until reciprocal semantic baseline is measured.

Reason: adding another model dependency before measuring mutual-KNN v1 obscures attribution and increases batch cost.

### DD-02 — cross-language Dream

Deferred.

Reason: multilingual embedding neighbourhood quality requires separate RU/KK/EN evaluation.

### DD-03 — contextual edges `edge(A,B|context)`

Deferred.

Potentially valuable but significantly changes graph identity/scoring semantics.

### DD-04 — metaplasticity / edge stability parameter

Deferred.

Existing hysteresis and online evidence should be measured first.

### DD-05 — Dream from Query Memory / replay episodes

Deferred.

Reason: creates circular-learning risks and requires explicit provenance/causal utility design.

### DD-06 — multiple active Dream owners / shard leases

Deferred until one owner cannot satisfy measured run duration/full-rescan horizon.

### DD-07 — hard-coded generic-chunk suppression

Deferred.

Measure hub false positives first; avoid domain-specific heuristics without evidence.

---

## 5. Implementation consistency checklist

Every implementation PR/commit should be reviewed against this list.

### Configuration

- [ ] Dream flags default OFF.
- [ ] Apply cannot be ON while Dream is OFF.
- [ ] Threshold ordering validated.
- [ ] Heartbeat interval validated against lease duration.
- [ ] All resource budgets configurable.
- [ ] Semantic policy fingerprint covers all material fields.

### Persistence

- [ ] Canonical pair ordering enforced in Java and DB constraints where practical.
- [ ] Candidate uniqueness uses policy fingerprint.
- [ ] Run/lease/checkpoint persistence separated from online graph.
- [ ] Fencing token monotonic.
- [ ] Checkpoint stale-token update rejected.

### Lifecycle/security

- [ ] Same ACL only.
- [ ] Generation identity exact.
- [ ] READY only.
- [ ] ACTIVE retention only.
- [ ] Current published generation only.
- [ ] TTL unexpired at discovery.
- [ ] TTL/lifecycle rechecked transactionally at apply.

### Evidence authority

- [ ] Dream cannot increment online counters.
- [ ] Dream cannot directly promote WARM/HOT.
- [ ] Dream semantic retirement preserves learned evidence.
- [ ] Infrastructure failures never count as semantic negatives.

### Multi-pod

- [ ] 4–6 schedulers result in one owner.
- [ ] Independent heartbeat.
- [ ] Stale owner graph mutation rejected.
- [ ] Stale owner checkpoint update rejected.
- [ ] Pod restart creates new process owner identity.
- [ ] Autoscaling does not multiply Dream workers.

### Performance

- [ ] Reverse ANN deduplicated/cached.
- [ ] ANN query budgets enforced.
- [ ] DB concurrency bounded.
- [ ] Max run duration enforced.
- [ ] Online traffic has priority/yield path.
- [ ] No unbounded in-memory candidate collection.

### Shadow

- [ ] Candidate/run/checkpoint state may mutate.
- [ ] Online graph cannot mutate.
- [ ] Request ranking cannot change.

### Apply

- [ ] Restricted semantic writer only.
- [ ] Pair update atomic.
- [ ] Node lock order canonical.
- [ ] Degree quota enforced under concurrency.

### Testing

- [ ] Unit config/fingerprint/state tests.
- [ ] Testcontainers DDL/lifecycle/fencing tests.
- [ ] 6-coordinator ownership test.
- [ ] owner death/takeover test.
- [ ] long-batch heartbeat test.
- [ ] shadow graph-unchanged test.
- [ ] concurrent graph-writer stress test.
- [ ] serving-load performance test.
- [ ] reviewed-corpus replay/calibration.

---

## 6. Exit criterion from specification phase

Specification phase is complete when:

```text
no unresolved P0 design ambiguity
implementation-spec-v1 is accepted as normative
first implementation starts with DREAM-1
```

The next engineering objective is deliberately narrow:

> Build a cluster-safe, resumable, bounded **shadow Dream** that can tell us whether reciprocal semantic consolidation is useful before it is permitted to mutate online graph memory.
