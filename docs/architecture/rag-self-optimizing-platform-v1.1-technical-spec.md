# AkmAI Self-Optimizing RAG Platform v1.1

Status: **Implementation**  
Branch: `feature/rag-self-optimizing-platform-v1.1`  
Baseline: `main@5c3767d7364c6d5a8785be3f6825771757e1c28d`  
Scope: release qualification and closure of gaps left after self-optimizing RAG v1.

## 1. Objective

v1.1 MUST turn the v1 self-optimizing foundation into a measurable, reproducible and operationally safe RAG runtime.

The release loop is:

`MEASURE -> RETRIEVE -> GENERATE -> VERIFY -> LEARN -> OFFLINE EVALUATE -> SHADOW -> CANARY -> PROMOTE/ROLLBACK -> MEASURE`

The branch MUST NOT add new retrieval algorithms. Existing vector, lexical, identifier, reference, concept, graph, fusion and reranking mechanisms may only be hardened, measured, calibrated or policy-controlled.

## 2. Global invariants

1. Security, ACL, lifecycle, authority and publication invariants remain fail-closed.
2. Learning storage is fail-soft and MUST NOT make an otherwise safe answer unsafe.
3. A learned policy MUST NOT enter production without immutable benchmark and performance evidence.
4. Authoritative source knowledge MUST NOT be rewritten by learning traffic.
5. A timeout MUST bound the underlying resource, not only the caller-facing future.
6. Every release-quality result MUST be attributable to git SHA, corpus version, embedding profile, retrieval policy, learning policy, grounding policy and hardware/runtime profile.
7. Cache/memory reuse MUST preserve ACL, generation/profile and policy isolation.

## 3. Work order

### P0-01 Resource deadlines — issue #39

Close the gap where caller-visible timeout can occur while discarded work continues consuming worker, JDBC connection or model-client capacity.

Required guarantees:

- strategy timeout interrupts the owned worker task;
- request timeout cancels unfinished work;
- dependent retrieval work MUST NOT start after the request deadline;
- a dependent step MUST NOT start when the remaining request budget is smaller than the configured resource/strategy timeout;
- `strategy-timeout <= request-timeout` is a startup configuration invariant;
- PostgreSQL retrieval runs under `retrievalTransactionTemplate` bounded by strategy timeout so Spring/JDBC can apply statement/transaction deadlines;
- retrieval embedding and answer/model calls use connect/read timeouts at the HTTP transport boundary;
- timeout outcome telemetry distinguishes timeout/rejection/interruption categories;
- deterministic saturation regression proves a timed-out worker is reusable by a following request.

Definition of Done:

- `ParallelRetrievalExecutorCancellationTest` proves strategy cancellation, request cancellation, worker recovery and no late dependent launch;
- `RetrievalProperties` rejects impossible timeout hierarchy;
- transaction/Ollama timeout tests remain green;
- CI green;
- issue #39 can be closed only after these checks pass.

### P0-02 Release-quality benchmark

Build a versioned corpus with at least 300 labelled queries covering LEGAL, MEDICAL and TECHNICAL domains, all supported languages where practical, and 20–30% unanswerable cases.

Required query classes include factual, paraphrase, lexical exact, identifier, identifier+semantic, reference, numeric, temporal, multi-intent, comparison, cross-language and unanswerable.

Required metrics:

- Recall@1/5/10;
- MRR;
- nDCG@10;
- Context Recall;
- Context Precision;
- Evidence Density;
- Abstention Precision/Recall;
- False Answer Rate;
- False Abstention Rate.

The release corpus MUST execute the real ingestion/retrieval path and MUST NOT use mocked retrievers or relevance-aware scorers.

### P0-03 Quality baseline comparator

Add immutable baseline reports and a deterministic comparator supporting:

- absolute metric floors;
- relative regression budgets;
- per-language/domain/query-class regressions;
- machine-readable PASS/FAIL evidence consumed by policy promotion.

### P0-04 Performance matrix

Extend the application-level performance harness with read-1/10/50/100, burst, ingest-only, mixed-light, mixed-heavy, slow-PostgreSQL, slow-model and slow-reranker scenarios.

At minimum qualify SMALL and MEDIUM corpus tiers before v1.1 completion. Capture p50/p95/p99, throughput, timeout/error rates and executor/DB/model saturation.

### P1-05 Query Memory HA

Make PostgreSQL the multi-replica source of truth with incremental/version-aware refresh, bounded lifecycle/TTL and explicit cache invalidation semantics. Caffeine remains L1 only.

### P1-06 Evidence-trained router

Implement the controlled lifecycle:

`learning events -> offline replay -> candidate -> benchmark/performance gates -> shadow -> canary -> approval`.

The router may reduce optional work or tune approved bounds; it MUST NOT bypass ACL, authority, identifier/reference requirements or publication fences.

### P1-07 Anti-poisoning

Add per-window reinforcement caps, independent-query support, duplicate suppression, feedback trust classes and anomaly/outlier signals. Repeated traffic from one source MUST NOT count as independent evidence.

### P1-08 Semantic grounding calibration

Calibrate `SUPPORTED / CONTRADICTED / INSUFFICIENT` on labelled RU/KK/EN contradiction datasets covering negation, modal conflict, exception omission, wrong subject, temporal conflict and partial support. Measure false-reject and false-accept rates before production enablement.

### P1-09 Trace/reproducibility unification

Benchmark, performance and learning MUST consume a common bounded execution-evidence model and emit git/model/policy/corpus/config attribution.

### P2-10 CanonicalDocument ingestion

Implement block-aware ingestion from `CanonicalDocument` while preserving AkmAI ownership of semantic chunking.

Required provenance chain:

`answer -> chunk -> canonical block -> page/section/bbox -> original source`.

## 4. Release gates

v1.1 is complete only when all P0 items are green and all P1/P2 items declared in scope have deterministic tests and reproducible reports.

A candidate MUST NOT be promoted when any of the following regresses beyond its approved budget:

- ACL/lifecycle correctness;
- Recall/MRR/nDCG;
- False Answer Rate / abstention quality;
- p95/p99 latency;
- throughput/saturation;
- grounding false-accept rate.

## 5. Non-goals

The branch MUST NOT introduce RAPTOR, new vector databases, autonomous ontology rewriting, uncontrolled online reinforcement learning, multi-hop graph expansion beyond currently approved bounds, new embedding models without benchmark evidence, or automatic modification of authoritative documents.

## 6. Implementation sequencing

Commits should remain phase-oriented:

- `RSP11-01` resource deadlines;
- `RSP11-10..14` release benchmark/baselines;
- `RSP11-20..24` performance matrix;
- `RSP11-30..34` memory HA and anti-poisoning;
- `RSP11-40..44` evidence-trained router;
- `RSP11-50..54` grounding calibration;
- `RSP11-60` trace/reproducibility;
- `RSP11-70` CanonicalDocument ingestion;
- `RSP11-80` integrated release qualification.
