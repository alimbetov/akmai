# AkmAI Self-Optimizing RAG Platform v1.1

Status: **Implemented — release qualification pending**  
Implementation merge: PR #57 (`8efbad751ace3b0171108237474cb403f0d2e6a0`)  
Deadline-contract hotfix: PR #59 (`607e4641db9270c74be57bba7167d3ed3ffc85a4`)  
Audited main: `607e4641db9270c74be57bba7167d3ed3ffc85a4`  
Scope: release qualification and closure of gaps left after self-optimizing RAG v1.

## 1. Objective

v1.1 turns the v1 self-optimizing foundation into a measurable, reproducible and operationally safe RAG runtime.

The release loop is:

`MEASURE -> RETRIEVE -> GENERATE -> VERIFY -> LEARN -> OFFLINE EVALUATE -> SHADOW -> CANARY -> PROMOTE/ROLLBACK -> MEASURE`

v1.1 does **not** introduce new retrieval algorithms. Existing vector, lexical, identifier, reference, concept, graph, fusion and reranking mechanisms are hardened, measured, calibrated and policy-controlled.

## 2. Global invariants

1. Security, ACL, lifecycle, authority and publication invariants remain fail-closed.
2. Learning storage is fail-soft and MUST NOT make an otherwise safe answer unsafe.
3. A learned policy MUST NOT enter production without immutable benchmark and performance evidence.
4. Authoritative source knowledge MUST NOT be rewritten by learning traffic.
5. A timeout MUST bound the underlying resource, not only the caller-facing future.
6. Every release-quality result MUST be attributable to git SHA, corpus version, embedding profile, retrieval policy, learning policy, grounding policy and hardware/runtime profile.
7. Cache/memory reuse MUST preserve ACL, generation/profile and policy isolation.
8. SHADOW evidence MUST NOT affect the user answer.
9. CANARY policy may affect only its bounded cohort and MUST be compared against simultaneous CONTROL evidence before approval.
10. Policy rollback and rejection MUST invalidate runtime caches immediately.

## 3. Implemented work

### P0-01 Resource deadlines — issue #39

Implemented guarantees:

- strategy timeout interrupts the owned worker task;
- request timeout cancels unfinished work;
- dependent retrieval work does not start after the request deadline;
- dependent/queued work does not start when the remaining request budget is smaller than the full configured strategy/resource timeout;
- `strategy-timeout < request-timeout` is a startup configuration invariant;
- PostgreSQL retrieval runs under `retrievalTransactionTemplate` bounded by strategy timeout;
- retrieval embedding/model calls use bounded HTTP transport timeouts;
- timeout telemetry distinguishes strategy, request-deadline, budget-exhaustion, resource, rejection and interruption outcomes;
- deterministic worker recovery and PostgreSQL timeout/connection-reuse regressions are present.

PR #59 aligned older timeout tests with the stricter v1.1 hierarchy without weakening production behavior.

### P0-02 Release-quality benchmark

Implemented controlled release corpus contract:

- 330 labelled queries;
- 255 answerable documents/cases;
- 75 unanswerable cases (22.7%);
- all 11 target languages;
- LEGAL / MEDICAL / TECHNICAL domains;
- FACTUAL, PARAPHRASE, LEXICAL_EXACT, IDENTIFIER_ONLY, IDENTIFIER_SEMANTIC, REFERENCE, NUMERIC, TEMPORAL, MULTI_INTENT, COMPARISON, CROSS_LANGUAGE and UNANSWERABLE classes;
- EASY / MEDIUM / HARD difficulty;
- deterministic document and child-chunk truth;
- production `HierarchicalChunker` identity verification.

The release corpus executes the real ingestion/retrieval/answer path and does not use mocked retrievers or relevance-aware scorers.

The controlled corpus is explicitly `CONTROLLED_SYNTHETIC`; it is a regression/release-engineering gate, not evidence of real-world domain accuracy.

### P0-03 Quality baseline comparator

Implemented:

- immutable candidate/baseline JSON reports;
- absolute overall metric floors;
- relative regression budgets;
- language/domain/query-class slice comparison;
- corpus/case-set compatibility checks;
- machine-readable quality evidence consumed by policy promotion/release qualification.

**Qualification still pending:** an approved baseline must be established from a reviewed live run and versioned at `benchmarks/rag-benchmark-v1/baselines/approved.json`.

### P0-04 Performance matrix

Implemented application-level profiles include:

- read concurrency 1 / 10 / 50 / 100;
- burst;
- ingest-only;
- mixed-light / mixed-heavy;
- resource-timeout/recovery regression coverage;
- SMALL physical corpus tier (default 256 documents);
- MEDIUM physical corpus tier (default 2048 documents).

Reports capture p50/p95/p99, throughput, success/abstention/failure counts and runtime metrics.

**Qualification still pending:** retained live SMALL/MEDIUM evidence on the reference/target runtime profile.

### P1-05 Query Memory HA

Implemented:

- PostgreSQL as source of truth;
- Caffeine as bounded L1 only;
- monotonic DB refresh revision for replica-safe incremental refresh;
- TTL/lifecycle cleanup;
- embedding-profile isolation;
- ACL isolation;
- retrieval/learning/grounding policy namespace isolation;
- fail-closed handling for legacy/unscoped rows and policy collisions.

### P1-06 Evidence-trained router

Implemented controlled lifecycle:

`learning events -> offline candidate -> benchmark/performance replay -> SHADOW -> CANARY -> APPROVED / REJECTED / ROLLED_BACK`

Key guarantees:

- candidate policies may modify only the approved router control surface;
- mandatory authority/identifier/reference safety is preserved;
- SHADOW uses bounded asynchronous replay after grounded answers and cannot affect the response;
- SHADOW promotion evidence counts only traffic where the candidate actually changes the plan;
- CANARY uses deterministic bounded cohort routing with simultaneous CONTROL evidence;
- promotion fails closed when samples/source diversity/retention/failure/latency evidence is insufficient;
- normal replacement uses `SUPERSEDED`; emergency rollback uses `ROLLED_BACK` semantics;
- runtime policy caches are invalidated immediately on promotion/reject/rollback.

### P1-07 Anti-poisoning

Implemented controls include:

- HMAC query/source fingerprints instead of raw learning identity;
- duplicate suppression;
- one independent feedback per request;
- explicit feedback trust classes;
- per-source/per-window caps;
- minimum distinct-query/source support;
- source concentration and anomaly signals;
- Query Memory duplicate reinforcement fencing at DB level;
- fail-closed promotion when independent evidence is insufficient.

### P1-08 Semantic grounding calibration

Semantic grounding is implemented as an optional post-deterministic gate:

`claim + cited evidence -> SUPPORTED / CONTRADICTED / INSUFFICIENT`

The deterministic grounding/citation rules remain authoritative. Semantic timeout/error is treated as insufficient evidence rather than acceptance.

A dedicated live RU/KK/EN calibration workflow exists for negation, modal conflict, exception omission, wrong subject, temporal conflict and partial support.

**Qualification still pending:** retain a passing live calibration report before enabling semantic grounding as a production release claim.

### P1-09 Trace/reproducibility unification

Implemented evidence contains runtime and request-level attribution including:

- git/runtime profile;
- corpus and embedding profile;
- approved retrieval/learning/grounding policy versions;
- effective CANARY/CONTROL rollout policy and cohort;
- selected/cited evidence counts;
- bounded stage timings/status.

Raw question/answer text is not required for rollout evidence.

### P2-10 CanonicalDocument ingestion

Implemented block-aware ingestion preserves the provenance chain:

`answer -> source -> chunk -> canonical block -> page/section/bbox -> original source`

Canonical block provenance is carried through semantic units and hierarchical chunks, participates in ingestion identity/fingerprint semantics, is persisted in published search metadata, and is exposed to the API only after published/ACL-eligible projection re-read.

## 4. Release gates

The integrated release gate is:

`.github/workflows/rag-v1.1-release-qualification.yml`

It requires all of the following to succeed for the same release SHA/tag:

1. release-quality benchmark + immutable baseline comparison;
2. SMALL and MEDIUM application-level performance qualification;
3. RU/KK/EN semantic-grounding calibration.

The final job emits `rag-v1.1-qualification.json` and sets `qualified=true` only when all three sub-gates succeed.

A candidate MUST NOT be promoted/released when any of the following regresses beyond its approved budget:

- ACL/lifecycle correctness;
- Recall/MRR/nDCG;
- Context Recall/Precision and evidence density;
- False Answer Rate / abstention quality;
- p95/p99 latency;
- throughput/saturation;
- grounding false-accept / false-reject budgets.

## 5. Current qualification state

Code implementation: **complete**.  
Normal `main` CI on the audited ref: **green**.  
Controlled benchmark contract: **implemented**.  
Approved immutable quality baseline: **pending**.  
Integrated live v1.1 qualification evidence: **pending**.  
Target-hardware SLO sign-off: **pending**.

Therefore v1.1 is **implemented but not yet externally release-qualified**.

See `docs/audit/post-v1.1-readiness-2026-10-08.md` for the current readiness decision and remaining release blockers.

## 6. Non-goals

v1.1 does not introduce RAPTOR, a new vector database, autonomous ontology rewriting, uncontrolled online reinforcement learning, unbounded multi-hop graph expansion, a new embedding model without benchmark evidence, or automatic modification of authoritative source documents.
