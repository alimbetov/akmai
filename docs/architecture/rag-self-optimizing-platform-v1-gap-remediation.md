# AkmAI Self-Optimizing RAG Platform v1 — Gap Remediation Blueprint

**Status:** Proposed implementation contract  
**Branch:** `feature/rag-self-optimizing-platform-v1`  
**Baseline branch head at specification start:** `a40014a7f7ca2af186c8141d87d7dbc5161209ab`  
**Scope:** close the remaining quality, performance, learning, adaptive-routing and semantic-grounding gaps of AkmAI as a production RAG module.  
**Depends on:** RAG Assurance RA-02…RA-08 already merged into the branch baseline.  

---

## 1. Objective

AkmAI already has a sophisticated WRITE/READ RAG architecture, hybrid retrieval, generation/lifecycle controls, Adaptive Chunk Graph, Self-Organizing Semantic Memory, deterministic grounding guards, retrieval attribution and a conservative adaptive planner.

The remaining problem is not the absence of retrieval features. The problem is that AkmAI still needs a closed, measurable and reversible improvement loop:

```text
MEASURE
   -> RETRIEVE
   -> GENERATE
   -> VERIFY
   -> RECORD LEARNING SIGNALS
   -> EVALUATE OFFLINE
   -> SHADOW
   -> CANARY
   -> PROMOTE / ROLLBACK
   -> MEASURE AGAIN
```

The branch is complete only when AkmAI can prove, with reproducible artifacts, that an adaptive policy improves or preserves correctness and retrieval quality while staying inside an approved latency/capacity envelope.

### Core principle

```text
AkmAI may learn HOW to retrieve authoritative knowledge.
AkmAI must not autonomously redefine WHAT authoritative knowledge is.
```

Source documents, published generations, exact identifiers, explicit references, ACL rules and retention/lifecycle state remain authoritative. Learning may affect bounded retrieval policy, learned associations, query memory and ranking parameters only through controlled promotion.

---

## 2. Non-negotiable safety invariants

Every phase in this branch MUST preserve these invariants.

### S-01 ACL isolation

No benchmark, memory, feedback, router, cache, graph or semantic-grounding component may return or learn from evidence outside the effective access scope.

### S-02 Published-generation isolation

Only the current ACTIVE/PUBLISHED eligible generation may become answer evidence or learning evidence.

### S-03 Authority preservation

Exact identifier and explicit reference authority MUST NOT be demoted by learned memory, adaptive routing, semantic similarity or learned ranking weights.

### S-04 Evidence-only learning

An answer or retrieval event may reinforce memory only after the configured evidence-quality boundary. At minimum, production learning MUST NOT use raw retrieval co-occurrence as strong positive evidence.

### S-05 No uncontrolled online self-modification

Runtime traffic MUST NOT directly rewrite production router thresholds, fusion weights, graph degree limits, grounding thresholds or promotion rules.

### S-06 Reversibility

Every learned/adaptive production feature requires a kill switch and deterministic fallback to the approved baseline path.

### S-07 Version attribution

Every benchmark, performance report and learning decision must identify the corpus, embedding profile, retrieval policy, learning policy, grounding policy and Git SHA that produced it.

### S-08 Fail-closed correctness

Security, lifecycle, citation and hard deterministic grounding failures remain fail-closed. Optional learning and optimization layers may fail soft only when fallback preserves those correctness boundaries.

---

## 3. Current baseline: what is already present

The implementation MUST reuse the current mechanisms rather than create parallel subsystems.

### 3.1 Correctness assurance

RA-02…RA-08 correctness contracts are part of the branch baseline. This branch does not reimplement those contracts. New features extend the same assurance model with new contract IDs where necessary.

### 3.2 Adaptive graph / self-organizing memory

Existing behavior already distinguishes:

- semantic prior (`semantic_similarity`);
- learned graph utility (`weight`);
- CANDIDATE / WARM / HOT lifecycle;
- bounded degree;
- citation/query evidence;
- decay and maintenance;
- graph-version isolation;
- ACL/generation-aware node identity.

This remains the retrieval-memory layer.

### 3.3 Semantic query memory

`SemanticQueryMemory` already records grounded/cited observations and clusters similar queries, but its storage is process-local Caffeine memory. This is not sufficient for restart durability or multi-replica consistency.

### 3.4 Adaptive retrieval planner

`AdaptiveRetrievalPlanner` already performs conservative heuristic lane reduction and supports shadow recommendations. It is the safe baseline planner, not yet a learned production router.

### 3.5 Quality tests

Current multilingual quality regression exercises production orchestration but still uses mocked retrieval repositories/scoring knowledge. It protects pipeline wiring, not real end-to-end retrieval quality.

### 3.6 Performance measurement

A storage-layout benchmark exists for PostgreSQL/pgvector and production Micrometer metrics exist, but there is no single application-level harness producing reproducible stage-by-stage latency, throughput and saturation baselines for the full RAG pipeline.

### 3.7 Grounding

Current deterministic grounding protects citations and important numeric/date consistency. It is deliberately not semantic entailment/NLI and cannot reliably detect non-numeric contradictions.

---

## 4. Gap register

| ID | Gap | Severity | Required closure in this branch |
| --- | --- | --- | --- |
| G-01 | No real corpus-backed full retrieval benchmark | P0 | Real pgvector/PostgreSQL/retrieval/fusion/rerank benchmark and JSON report |
| G-02 | No versioned quality baseline/promotion gate | P0 | Baseline store + absolute floors + allowed regression rules |
| G-03 | No full application-level performance harness | P0 | p50/p95/p99 + throughput + saturation + mixed-load scenarios |
| G-04 | Resource timeout/cancellation proof incomplete | P0 | Resource-boundary deadlines + saturation recovery tests |
| G-05 | Query memory is process-local | P0 | PostgreSQL-backed persistent/distributed memory with Caffeine L1 |
| G-06 | No unified learning event store | P0 | Privacy-safe, version-attributed learning events |
| G-07 | No explicit user feedback contract | P1 | Idempotent feedback API/store; feedback never directly mutates policy |
| G-08 | Adaptive planner is heuristic, not evidence-trained | P1 | Versioned policy candidate, offline replay, shadow, canary, rollback |
| G-09 | No policy registry/promotion record | P1 | Immutable policy versions and APPROVED/CANARY/REJECTED state |
| G-10 | Grounding lacks semantic contradiction detection | P1 | Claim/evidence verifier with SUPPORTED/CONTRADICTED/INSUFFICIENT |
| G-11 | No unified RAG execution trace domain artifact | P1 | Bounded execution trace reused by benchmark/perf/learning/debugging |
| G-12 | Anti-poisoning is incomplete outside graph controls | P1 | reinforcement caps, trust classes, anomaly/duplicate controls |
| G-13 | Cache/version isolation is not formalized for adaptive features | P1 | versioned cache keys + invalidation contracts |
| G-14 | Structured source/page provenance is not a first-class ingestion contract | P2 | RAG-side CanonicalDocument/provenance contract; FileService parser remains external |
| G-15 | Message Inbox/Outbox integration is not present | External | Define adapter/idempotency boundary only; broker/FileService implementation is out of this branch |

G-15 is intentionally not implemented as RabbitMQ/Kafka infrastructure in this RAG branch. AkmAI currently has no broker dependency. The branch must expose an ingestion command boundary that a future Inbox consumer can call without duplicating ingestion logic.

---

# 5. Target architecture

```text
                         AKMAI RAG

        +------------------ WRITE -------------------+
        |                                            |
CanonicalDocument -> semantic chunking -> publish -> knowledge
        |                                            |
        +--------------------------------------------+

        +------------------ READ --------------------+
        |                                            |
question -> query analysis -> policy/router -> lanes |
                                  |                 |
                                  v                 |
                    vector/lexical/id/ref/concept    |
                                  |                 |
                         fusion + rerank              |
                                  |                 |
                       graph expansion               |
                                  |                 |
                         bounded context              |
                                  |                 |
                           generation                |
                                  |                 |
                 citation + semantic grounding       |
                                  |                 |
                              answer                 |
        +--------------------------------------------+
                                  |
                                  v
                         RagExecutionTrace
                                  |
                 +----------------+----------------+
                 |                |                |
                 v                v                v
           benchmark/perf    learning events    diagnostics
                                  |
                                  v
                 persistent query/graph memory
                                  |
                                  v
                           offline replay
                                  |
                                  v
                        candidate policy
                                  |
                         shadow -> canary
                                  |
                                  v
                        explicit promotion
```

---

# 6. RSP-00 — Cross-cutting foundation

This phase is required before the five main feature phases so they share one data contract.

## RSP-00.1 `RagExecutionTrace`

Create a bounded domain artifact representing one RAG execution. It is not a replacement for OpenTelemetry spans; it is the structured result used by quality/performance/learning code.

Suggested structure:

```java
record RagExecutionTrace(
    String requestId,
    String corpusVersion,
    String embeddingProfileId,
    String retrievalPolicyVersion,
    String learningPolicyVersion,
    String groundingPolicyVersion,
    QueryTrace query,
    RetrievalTrace retrieval,
    ContextTrace context,
    GenerationTrace generation,
    CitationTrace citation,
    GroundingTrace grounding,
    TimingTrace timings,
    ExecutionStatus status
) {}
```

Production telemetry MUST NOT put document IDs, chunk IDs, raw query text or user IDs into Micrometer tag values. High-cardinality identities may exist only in bounded internal trace artifacts/logs with the configured retention/privacy policy.

### Acceptance

- trace creation is bounded;
- trace failure never changes answer correctness;
- benchmark and learning code can consume the same trace shape;
- sensitive/high-cardinality fields are excluded from metric tags.

## RSP-00.2 Policy version model

Introduce immutable version identifiers:

```text
corpusVersion
retrievalPolicyVersion
learningPolicyVersion
groundingPolicyVersion
```

A production request must be attributable to effective versions.

## RSP-00.3 Reproducibility metadata

Every JSON benchmark/performance artifact MUST include:

```text
gitSha
runId
timestamp
hardwareProfile
databaseVersion
ollamaVersion/model
embeddingProfile
corpusVersion
retrievalPolicyVersion
learningPolicyVersion
groundingPolicyVersion
configurationFingerprint
```

---

# 7. RSP-10 — Real `rag-benchmark-v1`

## 7.1 Purpose

Replace architecture confidence with measured retrieval evidence. Existing mocked/deterministic tests remain correctness/orchestration tests and MUST NOT be deleted.

## 7.2 Corpus layout

```text
benchmarks/rag-benchmark-v1/
    manifest.yaml
    documents/
        legal/
        medical/
        technical/
    queries/
        kk/
        ru/
        en/
        zh/
        de/
        fr/
        es/
        pt/
        it/
        tr/
        el/
    ground-truth/
    baselines/
```

## 7.3 Benchmark case

Minimum model:

```java
record RagBenchmarkCase(
    String id,
    String language,
    KnowledgeDomain domain,
    QueryClass queryClass,
    Difficulty difficulty,
    String question,
    Set<String> relevantChunkIds,
    Map<String, Integer> gradedRelevance,
    Set<String> relevantDocumentIds,
    Set<String> forbiddenChunkIds,
    Set<String> expectedIdentifiers,
    Set<String> expectedReferences,
    boolean answerable
) {}
```

## 7.4 Query classes

Mandatory classes:

```text
FACTUAL
PARAPHRASE
LEXICAL_EXACT
IDENTIFIER_ONLY
IDENTIFIER_SEMANTIC
REFERENCE
NUMERIC
TEMPORAL
MULTI_INTENT
COMPARISON
CROSS_LANGUAGE
UNANSWERABLE
```

## 7.5 Corpus size

`rag-benchmark-v1` target:

- at least 300 queries before branch sign-off;
- all 11 supported languages represented;
- legal, medical and technical domains represented;
- no supported language may have fewer than 15 cases;
- 20–30% of the final corpus should be `UNANSWERABLE` or insufficient-evidence cases;
- difficult cases must include competing near-duplicate/noise evidence.

The corpus may grow after v1, but accepted cases must never be silently replaced with easier cases.

## 7.6 Real execution rule

The sign-off benchmark MUST use:

```text
real ingestion
real PostgreSQL
real pgvector
real search projections
real vector retrieval
real lexical retrieval
real identifier/reference retrieval where applicable
real ResultFusion
real Reranker implementation
real lifecycle/ACL fences
```

The canonical quality sign-off MUST use the canonical embedding profile/model. A small PR smoke tier may use a reduced corpus/runtime, but it may not be presented as the canonical model-quality result.

## 7.7 Metrics

Required retrieval metrics:

```text
Recall@1
Recall@5
Recall@10
MRR
nDCG@10
```

Required context metrics:

```text
ContextRecall
ContextPrecision
EvidenceDensity
```

Required answerability metrics:

```text
AbstentionPrecision
AbstentionRecall
FalseAnswerRate
FalseAbstentionRate
```

Reports MUST include:

- overall;
- per language;
- per domain;
- per query class;
- per difficulty;
- answerable vs unanswerable.

## 7.8 Baseline gate

Do not require perfect ranking.

Use two simultaneous gates:

1. absolute safety/quality floor;
2. non-regression against approved baseline.

Initial thresholds are configuration, not permanent constants. Suggested bootstrap gates:

```text
Recall@10 >= 0.90
MRR >= 0.80
nDCG@10 >= 0.82
FalseAnswerRate <= 0.02
ACL violations = 0
stale/unpublished evidence = 0
```

After the first real baseline is accepted, change control is relative:

```text
MRR delta >= -0.01
nDCG@10 delta >= -0.01
Recall@10 delta >= -0.01
FalseAnswerRate must not materially regress
```

Any threshold change requires an explicit committed baseline decision record.

## 7.9 Output

```text
target/rag-benchmark/rag-benchmark-v1.json
```

The output must contain per-case results as well as aggregates so regressions can be localized.

## 7.10 Mutation proof

At least one deliberately degraded retrieval/reranking policy must fail the real benchmark gate. This proves the gate can detect meaningful regressions.

---

# 8. RSP-20 — Full performance harness

## 8.1 Purpose

Measure the application pipeline, not only pgvector storage layout.

## 8.2 Stage timing

READ stages:

```text
QUERY_ANALYSIS
PLANNING
QUERY_EMBEDDING
IDENTIFIER
VECTOR
LEXICAL
CONCEPT
REFERENCE
FUSION
RERANK
GRAPH_EXPANSION
AUTHORITY_FILTER
CONTEXT_BUDGET
CONTEXT_REVALIDATION
GENERATION
CITATION
GROUNDING
TOTAL
```

WRITE stages:

```text
NORMALIZE
STRUCTURE
CHUNKING
ENRICHMENT
IDENTIFIERS
REFERENCES
EMBEDDING
PROJECTION_WRITE
VECTOR_WRITE
PUBLICATION
SEMANTIC_LINKING
TOTAL
```

## 8.3 Metrics per stage

```text
count
min
p50
p90
p95
p99
max
mean
error count
timeout count
```

Primary decision percentiles are p95 and p99.

## 8.4 Throughput

READ:

```text
requests/sec
successful grounded answers/sec
abstentions/sec
```

WRITE:

```text
documents/sec
chunks/sec
embeddings/sec
published generations/sec
```

## 8.5 Saturation

Collect at least:

```text
retrieval executor active/queue/rejected
reranker executor active/queue/rejected
ingestion executor active/queue/rejected
DB pool active/idle/pending/max
PostgreSQL query latency
Ollama in-flight requests
CPU
heap
GC pause
```

## 8.6 Scenario matrix

Mandatory scenarios:

```text
read-1
read-10
read-50
read-100
read-burst
ingest-only
mixed-light
mixed-heavy
slow-postgres
slow-ollama
slow-reranker
```

Dataset tiers:

```text
SMALL   10k vectors
MEDIUM  100k vectors
LARGE   1M vectors
XL      5M vectors
```

Normal CI uses SMALL. MEDIUM is a scheduled/nightly tier. LARGE/XL are explicit capacity experiments and must record hardware/database configuration.

## 8.7 Warmup and repeatability

- warm JVM/model/DB/index before measured iterations;
- fixed query set and random seed;
- report cold-start separately;
- repeat runs and report variance;
- do not compare baselines from materially different hardware profiles without marking them incomparable.

## 8.8 Performance JSON

```text
target/rag-performance/<scenario>.json
```

Each report includes stage histograms, throughput, saturation maxima and environment metadata.

## 8.9 Regression gates

Default relative gates after approved baseline:

```text
p95 regression <= 15%
p99 regression <= 20%
throughput regression <= 10%
nominal executor rejection = 0
nominal error-rate regression = 0
```

Absolute generation SLOs are hardware-profile specific and must not be hard-coded globally.

## 8.10 Resource-boundary deadline closure

Logical Future cancellation is not sufficient evidence of resource release.

Add/verify:

- JDBC statement/query timeout;
- HTTP/model connect/read/request timeout;
- request deadline propagation;
- dependent work not started after deadline;
- cancellation-aware task ownership where interruption is meaningful.

Required test:

```text
block strategy/backend
 -> request timeout
 -> worker/connection/model capacity returns promptly
 -> subsequent nominal request succeeds without zombie backlog
```

---

# 9. RSP-30 — Persistent Learning Memory v2

## 9.1 Persistent query memory

Replace process-local source-of-truth memory with PostgreSQL-backed persistence. Caffeine remains optional L1 cache only.

Suggested tables:

```text
rag_query_memory_cluster
rag_query_memory_observation
```

### Cluster fields

```text
cluster_id
embedding_profile_id
centroid
required_access_scope_fingerprint
observation_count
created_at
updated_at
last_reinforced_at
memory_version
```

ACL membership itself may be stored in a normalized child relation if a fingerprint alone is insufficient for enforcement. A fingerprint is never a substitute for the actual authorization predicate.

### Observation fields

```text
observation_id
cluster_id
query_fingerprint
normalized_query_redacted_or_null
grounded_answer_excerpt_or_null
source_refs
citation_count
grounding_status
observed_at
expires_at
```

Raw query persistence is disabled by default. Prefer keyed privacy-safe fingerprints and bounded/redacted diagnostic text only when explicitly configured.

## 9.2 Multi-replica behavior

Required properties:

- restart does not erase memory;
- replica A and B observe a consistent source of truth;
- updates use optimistic/transactional concurrency;
- cache entries are invalidated/version-checked;
- embedding-profile change prevents incompatible centroid reuse.

## 9.3 Memory lifecycle

Introduce bounded lifecycle:

```text
NEW -> ACTIVE -> STALE -> PURGED
```

Rules must include:

- max clusters;
- max observations per cluster;
- age/TTL;
- minimum reinforcement;
- profile/version retirement;
- ACL-aware purge.

## 9.4 Learning Event Store

Add append-only, privacy-safe learning events.

Suggested event fields:

```text
event_id
request_id
query_fingerprint
language
query_class
domain
retrieval_policy_version
learning_policy_version
grounding_policy_version
embedding_profile_id
corpus/generation identity
lane_outcomes
selected_source_refs
cited_source_refs
graph_source_refs
answer_status
grounding_status
explicit_feedback_id nullable
stage_timings
created_at
```

The store is for reproducible offline evaluation. It is not a direct production ranking table.

## 9.5 Explicit feedback

Add an idempotent API contract, for example:

```text
POST /api/rag/feedback
```

Payload model:

```text
requestId
feedbackId / idempotency key
rating: POSITIVE | NEGATIVE
reason:
  GOOD
  WRONG_ANSWER
  WRONG_EVIDENCE
  MISSING_EVIDENCE
  OUTDATED_EVIDENCE
  BAD_CITATION
  INCOMPLETE
optional relevant source refs
optional irrelevant source refs
```

Rules:

- feedback is scoped to an authenticated/authorized request context;
- replay is idempotent;
- source refs are revalidated before use;
- feedback cannot directly mutate graph weights/router policy;
- contradictory feedback from repeated/identical actors is bounded and treated as evidence, not truth.

## 9.6 Anti-poisoning controls

Mandatory:

```text
per-query-fingerprint reinforcement saturation
per-time-window reinforcement cap
minimum distinct query support
minimum evidence-quality level
feedback trust class
bot/replay duplicate suppression
outlier monitoring
candidate-before-active promotion
age decay
```

No single request, user, feedback item or repeated query fingerprint may immediately promote a learned policy/edge to production authority.

---

# 10. RSP-40 — Evidence-trained Adaptive Router

## 10.1 Reuse current planner

The existing `AdaptiveRetrievalPlanner` remains the conservative rule-based baseline and fallback.

Do not replace it with an opaque model first.

## 10.2 Router responsibility

The router may choose a bounded retrieval policy:

```text
lanes
topK per lane
rerank depth
graph expansion allowance
```

It MUST NOT:

- bypass ACL;
- suppress an exact IDENTIFIER lane when a recognized exact identifier is required by the baseline authority contract;
- make unpublished/expired evidence eligible;
- change source authority tiers;
- disable all semantic recall for a semantic query unless a proven exact-authority path exists;
- directly change fusion/graph thresholds outside its approved policy version.

## 10.3 Feature vector

Allowed routing features include:

```text
language
query length
identifier present
reference present
multi-intent flag
semantic concept confidence
query class
semantic-memory match strength
historical lane utility aggregates
approved latency-cost statistics
```

Do not use user identity as a routing feature.

## 10.4 Policy representation

Start with interpretable/versioned policies, e.g. decision table or small bounded scoring model.

Example policy output:

```text
IDENTIFIER_ONLY:
  IDENTIFIER

IDENTIFIER_SEMANTIC:
  IDENTIFIER -> VECTOR + LEXICAL -> REFERENCE

CONCEPTUAL_EXACT:
  VECTOR + CONCEPT + REFERENCE

GENERIC:
  VECTOR + LEXICAL + REFERENCE
```

Learning optimizes candidate policy/budgets from benchmark + learning events; it does not perform unconstrained online reinforcement learning.

## 10.5 Policy registry

Add immutable policy records:

```text
policy_id
policy_version
policy_payload/config fingerprint
training/evaluation corpus version
created_at
status:
  CANDIDATE
  REJECTED
  SHADOW
  CANARY
  APPROVED
  RETIRED
metrics_summary
parent_policy_version
```

Only one APPROVED policy is active per compatible runtime scope/profile unless an explicit canary split is active.

## 10.6 Offline replay

For each candidate:

```text
same corpus snapshot
same embedding profile
same query set
baseline policy vs candidate policy
```

Evaluate paired per-query deltas.

Required hard gates:

```text
ACL violations = 0
lifecycle violations = 0
identifier/reference authority violations = 0
quality non-inferiority
latency/capacity inside envelope
```

Use existing adaptive-memory statistical protocol for paired analysis where enough samples exist.

## 10.7 Shadow mode

Shadow routing computes a candidate policy but executes the approved policy.

Record:

```text
lane differences
estimated avoidable work
predicted candidate costs
query class
baseline outcome
```

Shadow output never changes answer evidence.

## 10.8 Canary

After offline approval:

```text
small traffic slice
fixed evaluation horizon
predeclared success/failure criteria
immediate kill switch
```

Do not stop early merely because an intermediate metric looks favourable.

## 10.9 Promotion

Promotion must create an immutable decision record:

```text
candidate version
baseline version
corpus snapshot
quality deltas
confidence intervals/statistical evidence
latency/capacity deltas
slice regressions
security result
APPROVED / REJECTED
```

---

# 11. RSP-50 — Semantic Grounding v2

## 11.1 Objective

Detect semantic contradiction and unsupported factual claims that deterministic citation/numeric validation cannot detect.

## 11.2 Pipeline

```text
generated answer
 -> deterministic claim segmentation
 -> claim + cited evidence mapping
 -> deterministic hard guards
 -> semantic claim/evidence verifier
 -> aggregate grounding decision
```

## 11.3 Verdict model

```text
SUPPORTED
CONTRADICTED
INSUFFICIENT
```

Per claim, retain:

```text
claim text/hash
cited source numbers
verdict
confidence
verifier version
reason category
```

## 11.4 Precedence

Deterministic hard failures take precedence over semantic verifier optimism.

Examples:

- invalid citation -> reject regardless of semantic score;
- numeric mismatch -> reject regardless of semantic score;
- expired/ineligible evidence -> reject before semantic verification.

## 11.5 Semantic verifier interface

Introduce an implementation-neutral interface so AkmAI can use a local NLI model or bounded local LLM verifier without coupling orchestration to one model.

```java
interface ClaimEvidenceVerifier {
    ClaimEvidenceVerdict verify(Claim claim, List<Evidence> evidence);
}
```

## 11.6 Latency control

- batch claim verification where supported;
- hard verifier timeout;
- max claims per answer;
- max evidence tokens per claim;
- explicit fallback policy.

For regulated/safety-oriented configuration, timeout/unavailable verifier should produce `INSUFFICIENT` when semantic verification is mandatory. For lower-assurance profiles, semantic verification may be optional but the response must be marked accordingly internally.

## 11.7 Calibration dataset

Add labeled grounding cases:

```text
entailed paraphrase
negation contradiction
modal change (may/must/must not)
exception omission
wrong subject/object
wrong temporal condition
numeric contradiction
partial support
multi-source support
insufficient evidence
```

Include KK/RU/EN at minimum for v1 semantic grounding sign-off, then expand to all supported languages before claiming uniform multilingual semantic-grounding quality.

## 11.8 Grounding metrics

```text
claim support precision
contradiction recall
unsupported-claim rate
false-reject rate
verifier p50/p95/p99
```

No semantic verifier is promoted solely on aggregate accuracy; high-risk contradiction slices must be reported separately.

---

# 12. RSP-60 — Cache/version isolation

Adaptive/self-learning features create stale-cache risk. Formalize cache keys.

## 12.1 Query embedding cache

Key:

```text
embeddingProfileId + normalizedQueryHash
```

## 12.2 Retrieval/result cache (if introduced)

Key must include at least:

```text
effective ACL/scope fingerprint
query fingerprint
published corpus/generation fingerprint
embedding profile
retrieval policy version
graph version
ontology version
```

Never cache a final retrieval result by question text alone.

## 12.3 Reranker cache (optional)

```text
rerankerVersion + queryHash + candidateSetHash
```

## 12.4 Cache contracts

Tests must prove:

- no cross-ACL reuse;
- profile/version changes invalidate incompatible entries;
- generation publication does not serve stale final evidence;
- cache failure falls back to source-of-truth retrieval.

---

# 13. RSP-70 — Structured ingestion/provenance readiness

This closes the RAG-side part of the document-format gap without turning AkmAI into FileService.

## 13.1 `CanonicalDocument` contract

Add a versioned internal/request model capable of carrying blocks:

```text
Document
  id
  version
  title
  language
  domain
  source
  access scope
  blocks[]
```

Block fields:

```text
blockId
type: HEADING | PARAGRAPH | TABLE | LIST | CODE | FOOTNOTE | IMAGE_TEXT
text
pageNumber optional
sectionPath
sourceOffset optional
bbox optional
metadata
```

## 13.2 Backward compatibility

`POST /api/knowledge/text` remains supported. The structured ingestion path may adapt text into a single/simple block representation.

## 13.3 Provenance preservation

Chunk/enrichment/citation metadata must be able to resolve:

```text
answer -> chunk -> canonical block(s) -> page/section -> original source identifier
```

AkmAI need not store the binary file.

## 13.4 Future Inbox compatibility

Refactor ingestion behind a command/service boundary that can be invoked identically from:

```text
HTTP controller
future message Inbox consumer
integration tests
```

Do not add RabbitMQ/Kafka solely for this branch. Future FileService owns parsing/storage/outbox; future AkmAI integration owns inbox/deduplication and calls the same ingestion command.

---

# 14. Test strategy

## 14.1 PR tier

Target: fast enough for normal development.

Run:

```text
RAG correctness contracts
benchmark metric/unit tests
small real PostgreSQL retrieval smoke corpus
learning persistence/invariants
router policy contracts
semantic grounding deterministic/calibration smoke
cache/version isolation
```

No requirement for production-size Ollama capacity run on every PR.

## 14.2 Main tier

Run:

```text
full Testcontainers integration
medium quality corpus where feasible
policy replay tests
persistent-memory multi-instance/concurrency tests
fault-injection timeout recovery
```

## 14.3 Nightly tier

Run:

```text
canonical live Ollama embedding quality benchmark
full multilingual corpus
SMALL/MEDIUM performance matrix
mixed ingestion + retrieval
semantic verifier live evaluation
adaptive policy shadow replay
```

## 14.4 Release tier

Run and retain artifacts for:

```text
canonical rag-benchmark-v1
approved hardware performance baseline
LARGE capacity run as required
fault injection
canary decision evidence
learning-memory durability
semantic grounding benchmark
```

---

# 15. Observability requirements

Required low-cardinality metric families:

```text
akmai.rag.stage.latency{stage,outcome}
akmai.rag.stage.requests{stage,outcome}
akmai.rag.policy.requests{policyVersion,mode}
akmai.rag.learning.events{type,outcome}
akmai.rag.memory.lookup{outcome}
akmai.rag.memory.persist{outcome}
akmai.rag.feedback{rating,reason}
akmai.rag.grounding.claims{verdict}
akmai.rag.router.delta{action,strategy}
```

Do not tag metrics with:

```text
query text
request ID
document ID
chunk ID
user ID
feedback ID
```

High-cardinality diagnostics belong in traces/logs/artifacts under bounded retention.

---

# 16. Implementation sequence inside the single branch

Use one branch, but keep commits/PR-reviewable steps small and phase-ordered.

```text
RSP-00  execution trace + policy/version foundation

RSP-10  benchmark model/corpus loader
RSP-11  real retrieval benchmark runner
RSP-12  metrics + JSON baseline comparator
RSP-13  answerability/abstention cases
RSP-14  benchmark mutation proof + CI

RSP-20  stage instrumentation
RSP-21  performance runner/report schema
RSP-22  load/mixed scenarios
RSP-23  saturation/fault injection
RSP-24  resource-boundary deadline verification

RSP-30  persistent query memory schema/repository
RSP-31  Caffeine L1 + multi-replica consistency
RSP-32  learning event store
RSP-33  feedback API/idempotency
RSP-34  anti-poisoning/lifecycle

RSP-40  retrieval policy model/registry
RSP-41  offline replay evaluator
RSP-42  router candidate generation
RSP-43  shadow mode
RSP-44  canary/promotion/rollback

RSP-50  claim segmentation/evidence mapping
RSP-51  semantic verifier interface
RSP-52  contradiction/insufficient policy
RSP-53  multilingual calibration corpus
RSP-54  semantic-grounding performance/quality gate

RSP-60  cache/version isolation contracts
RSP-70  CanonicalDocument/provenance RAG-side contract
RSP-80  integrated end-to-end evaluation and final sign-off
```

No later adaptive phase may be enabled in production before the earlier measurement foundation is green.

---

# 17. Definition of Done for the branch

The branch is complete only when all of the following are true.

## Correctness

```text
ACL violations = 0
unpublished/stale-generation evidence = 0
invalid citation acceptance = 0
expired evidence acceptance = 0
mixed incompatible embedding profile acceptance = 0
```

Existing RA contract suite remains green.

## Quality

A committed/retained canonical real-pipeline report exists with:

```text
Recall@1/5/10
MRR
nDCG@10
Context Recall/Precision
Abstention Precision/Recall
False Answer Rate
```

and per-language/domain/query-class slices.

## Performance

At least SMALL and MEDIUM reports exist with:

```text
stage p50/p95/p99
throughput
executor/DB/model saturation
mixed read/write workload
fault-injection recovery
```

## Learning

- query memory survives restart;
- query memory works across replicas through one source of truth;
- learning events are reproducible and version-attributed;
- explicit feedback is idempotent and cannot directly mutate production policy;
- poisoning/replay amplification is bounded.

## Adaptive routing

- approved baseline policy exists;
- candidate policies are evaluated offline;
- shadow mode is non-invasive;
- canary has kill switch;
- promotion/rollback records are immutable/reproducible;
- no policy can violate authority/security hard rules.

## Grounding

- semantic contradictions are detected by the calibrated verifier;
- deterministic citation/numeric guards remain authoritative;
- unsupported semantic claims produce configured abstention/rejection behavior;
- grounding quality and latency are reported.

## Provenance readiness

- structured blocks/page provenance can flow through ingestion/chunks/sources;
- binary file storage/parser remains outside AkmAI;
- ingestion service boundary can later be called from an Inbox consumer without duplicating RAG logic.

## Reproducibility

Every approved result is attributable to:

```text
git SHA
corpus version
embedding profile
retrieval policy version
learning policy version
grounding policy version
hardware/environment fingerprint
```

---

# 18. Explicit non-goals

The branch MUST NOT expand into unrelated research without measured need.

Out of scope unless a benchmark demonstrates necessity:

```text
new vector database
new graph database
RAPTOR/recursive summary hierarchy
multi-hop graph traversal > 1
uncontrolled online RL
automatic ontology rewriting
automatic source-document rewriting
LLM-generated summaries as authoritative citation evidence
new embedding model migration solely for experimentation
broker/FileService implementation
```

---

# 19. Final promotion gate

Final branch sign-off is lexicographic:

```text
1. SECURITY
   zero violations

2. CORRECTNESS
   RA contracts + lifecycle/authority invariants green

3. QUALITY
   real-pipeline benchmark passes approved floors/non-regression

4. GROUNDING
   false/unsupported answer behavior within approved limits

5. PERFORMANCE
   p95/p99/throughput/saturation inside approved envelope

6. LEARNING SAFETY
   persistence, anti-poisoning, replay and policy controls green

7. ADAPTIVE VALUE
   candidate policy shows defensible quality/latency/cost value

8. REPRODUCIBILITY
   all reports and promotion decisions are version-attributed
```

Do not collapse these gates into one weighted score. Security/correctness are hard gates. Quality and latency are separate decision dimensions. Prefer the simplest policy on the Pareto frontier once all hard gates pass.
