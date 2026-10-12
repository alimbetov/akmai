# AkmAI current runtime architecture

Classification: **CURRENT**  
Synchronized against: `feature/retrieval-correctness-hardening-v1` / PR #95, based on `main@0966cc2bb22cfe924cc55a8d7cafd16ccf1c681a`  
Date: 2026-10-12

This document describes the executable AkmAI runtime at a system level. Design documents in this directory provide deeper rationale; this file is the current-state map for the implementation carried by the branch above.

## 1. System purpose

AkmAI is a local-first multilingual evidence RAG engine for legal, medical and technical knowledge bases. It is designed around retrieval correctness, ACL isolation, lifecycle safety, provenance, citations, grounding, local model execution and bounded self-optimization.

The system is not a direct `question -> LLM` wrapper. The LLM is the final synthesis stage over evidence selected by retrieval and verification components.

## 2. Runtime planes

AkmAI has five cooperating planes:

1. **INGEST** — canonicalize, structure, chunk, enrich, embed and publish knowledge.
2. **RETRIEVE** — analyze a query and execute applicable retrieval lanes under ACL/lifecycle constraints.
3. **GENERATE** — fuse, rerank, expand and budget evidence before local generation.
4. **VERIFY** — validate citations and grounding before accepting an answer.
5. **LEARN / OPERATE** — collect bounded observations, maintain adaptive memory, evaluate policies and operate the service.

```mermaid
flowchart TB
    subgraph INGEST
      SRC[Canonical content]
      NORM[Normalize / structure]
      CHUNK[Semantic parent-child chunking]
      ENRICH[Identifiers / references / concepts / provenance]
      EMB[Embeddings]
      PERSIST[Generation persistence]
      PUB[Atomic publication]
      SRC --> NORM --> CHUNK --> ENRICH
      ENRICH --> EMB
      ENRICH --> PERSIST
      EMB --> PERSIST --> PUB
    end

    subgraph RETRIEVE
      Q[Question]
      QA[Query analysis]
      PLAN[RetrievalPlanner]
      V[Vector]
      L[Lexical]
      I[Identifier]
      R[Reference]
      C[Concept when enabled]
      F[ResultFusion]
      RR[Reranker]
      Q --> QA --> PLAN
      PLAN --> V
      PLAN --> L
      PLAN --> I
      PLAN --> R
      PLAN --> C
      V --> F
      L --> F
      I --> F
      R --> F
      C --> F
      F --> RR
    end

    subgraph EXPAND_VERIFY
      K[Structural KnowledgeExpansion]
      GS[AdaptiveGraphShadowExpansion]
      GO[AdaptiveGraphOnlineExpansion]
      GC[AdaptiveGraphCompetitiveAdmission]
      PARENT[Parent context expansion]
      AUTH[Temporal / authority filtering]
      SEL[Eligibility-aware context selection]
      DIV[Diversity filtering]
      BUDGET[ContextBudget]
      REVAL[PublishedContextRevalidator]
      CTX[ContextAssembler]
      LLM[Local Ollama generation]
      CIT[CitationValidator]
      DG[Deterministic grounding]
      SG[Semantic grounding when enabled]
      RR --> K --> GS --> GO --> GC --> PARENT --> AUTH --> SEL --> DIV --> BUDGET --> REVAL --> CTX --> LLM --> CIT --> DG --> SG
    end

    subgraph LEARN_OPERATE
      UTIL[Adaptive graph utility]
      AL[Association learning]
      QM[Persistent query memory]
      POLICY[Offline / SHADOW / CANARY policy evaluation]
      MET[Metrics / traces / diagnostics]
      MAINT[Lifecycle / graph maintenance]
      SG --> UTIL
      SG --> AL
      SG --> QM
      QM --> POLICY
      AL --> MAINT
    end

    PUB --> V
    PUB --> L
    PUB --> I
    PUB --> R
    PUB --> C
```

## 3. Ingest path

The ingest path converts source content into a generation-scoped, access-scoped searchable representation.

Core responsibilities:

- normalize text while preserving semantic structure;
- extract structural units and section paths;
- apply domain-aware semantic classification;
- protect atomic legal/medical/technical units from unsafe splitting;
- produce parent/child semantic chunks within configured token budgets;
- extract exact business identifiers and document references;
- attach provenance and semantic metadata;
- generate embeddings using the active embedding profile;
- persist generation payloads;
- publish a complete generation atomically.

The active embedding profile is versioned. AkmAI must not mix embeddings from different profiles even when vector dimensionality is the same.

## 4. Retrieval lanes

The production pipeline supports independent retrieval evidence sources:

- **VECTOR** — semantic similarity in pgvector/HNSW;
- **LEXICAL** — PostgreSQL text/trigram retrieval;
- **IDENTIFIER** — exact business identifier lookup;
- **REFERENCE** — explicit document-reference/anchor relationships;
- **CONCEPT** — semantic concept retrieval when enabled by the active configuration/policy.

These lanes have different authority semantics. Exact identifiers and explicit references are not merely high-scoring vector hits.

All bounded candidate-producing lanes enforce one canonical read-side lifecycle rule before their authoritative `LIMIT/topK` is allocated:

```text
READY
+ ACTIVE retention
+ unexpired TTL
+ caller-authorized access level
+ exact document identity
+ exact published generation
```

VECTOR preserves its raw HNSW probe for index efficiency. The raw probe is not authoritative evidence: candidate eligibility is evaluated after the probe, ineligible-candidate crowding triggers bounded ANN expansion, and the existing fenced fallback remains available. LEXICAL, CONCEPT, IDENTIFIER and REFERENCE apply the same eligibility semantics before their own bounded result truncation.

The Adaptive Association Graph is deliberately **not** a peer RRF lane. It is a bounded post-rerank expansion/memory layer. See [`adaptive-graph-runtime.md`](adaptive-graph-runtime.md).

## 5. Retrieval flow

```text
Question
  -> QueryChunker / query analysis
       ORIGINAL decomposition is mandatory
       MULTI_QUERY consumes remaining expansion capacity only
  -> RetrievalPlanner
  -> applicable retrieval lanes
       canonical candidate eligibility before bounded lane truncation
  -> ResultFusion
  -> Reranker
  -> structural KnowledgeExpansion
  -> adaptive graph observation/online expansion when enabled
  -> competitive admission
  -> parent expansion
  -> temporal authority filtering
  -> pre-budget lifecycle eligibility
  -> diversity + context budget
  -> final published-context TOCTOU revalidation
  -> at most one bounded rebuild/backfill if final revalidation releases capacity
  -> full final revalidation of the rebuilt selection
```

Important invariants:

- ACL is applied before and during retrieval; it is not a late redaction step.
- A stale, expired, non-READY or unpublished candidate cannot consume a bounded authoritative lane slot ahead of an eligible candidate.
- Candidate-time lifecycle filtering does not replace existing intermediate or final safety fences.
- TTL expiry is synchronously fenced even if asynchronous retention cleanup has not run yet.
- Parent evidence is created before temporal authority is applied, so a parent cannot inherit a child's authority decision without being evaluated itself.
- Context is lifecycle-eligible before budgeting. If the final TOCTOU fence removes selected evidence, the selector may rebuild exactly once from lower-ranked eligible candidates while reapplying diversity and budget rules.
- A second TOCTOU invalidation returns a smaller safe context rather than starting an unbounded retry loop.
- Final context is revalidated immediately before generation.

## 6. Generation and verification

Generation receives only bounded evidence assembled from the final context.

```text
final evidence
  -> ContextAssembler
  -> local LLM
  -> CitationValidator
  -> deterministic grounding
  -> optional semantic claim/evidence verification
  -> answer or insufficient-information response
```

A generated answer is not accepted solely because the model returned text. Invalid citations or failed grounding cause the request to fail closed to an insufficient-information result.

## 7. Adaptive Association Graph

The graph is derived retrieval memory over generation-aware chunk identities.

Node identity:

```text
(access_level, document_id, generation, chunk_id)
```

Edge lifecycle:

```text
CANDIDATE -> WARM -> HOT
     |         |      |
     +--decay--+--decay
          -> DECAYED -> purge
```

Current default graph feature switches are off. Enabling learning does not itself enable online influence. Learning, maintenance, shadow expansion, online expansion and competition are separately controlled.

The graph performs bounded one-hop adjacency lookup in PostgreSQL; v1 does not require a dedicated graph database.

## 8. Self-optimizing RAG

AkmAI self-optimization changes retrieval behavior, not authoritative knowledge.

The safe promotion model is:

```text
MEASURE
  -> build candidate policy/memory
  -> OFFLINE evaluate
  -> SHADOW
  -> CANARY + CONTROL
  -> APPROVE or ROLLBACK
  -> continue measurement
```

Persistent Query Memory and learning events are namespaced and bounded. Query/source fingerprints are privacy-safe and do not require retaining raw user identity.

## 9. Storage model

Primary runtime storage is PostgreSQL with pgvector.

Major data classes include:

- canonical generation/lifecycle records;
- published search projections;
- vector payload/manifests;
- identifiers;
- explicit reference edges;
- adaptive chunk associations;
- self-optimizing/query-memory state;
- audit/runtime parameter state.

Exact projection materialization uses bounded key batches rather than a per-key `UNION ALL` plan. Requests are de-duplicated in first-occurrence order and queried in batches of at most 256 composite `(accessLevel, documentId, generation, chunkId)` identities.

Executable schema truth is in Liquibase migrations under `src/main/resources/db/changelog`.

## 10. Lifecycle and retention

Document generations are first-class runtime identities. Re-ingesting a document creates a new generation; old graph/vector/reference state does not silently become the new generation's state.

Retirement/purge must account for all generation payload families, including adaptive associations. Reconciliation and repair verify residual payload rather than assuming cleanup succeeded.

The canonical read-side lifecycle predicate is shared through `PublishedLifecycleSql`, while repository-specific SQL remains responsible for exact ACL/document/generation joins. This avoids semantic drift between candidate admission and final lifecycle fences.

## 11. Security boundaries

Security is intentionally coupled to retrieval routing:

- access levels scope retrieval and graph reads/writes;
- learned graph edges never cross ACL boundaries;
- non-local deployments fail startup for unsafe authentication/local-bypass/default-secret combinations;
- request body limits are transport-level bounds, including unknown/chunked bodies;
- secrets and query-fingerprint keys must not be emitted to logs.

## 12. Concurrency and timeouts

Retrieval uses bounded executors and explicit request/strategy deadlines. Resource-level JDBC/model transport timeouts are required so a logical timeout does not leave unbounded zombie work holding workers or connections.

The configuration invariant is:

```text
strategy-timeout < request-timeout
```

Dependent stages must not start when the remaining request budget is insufficient.

Context selection is intentionally not a serializable snapshot. It uses a pre-budget eligibility read plus a late synchronous final fence; at most one deterministic rebuild/backfill round handles late invalidation. This bounds database work even when lifecycle state changes concurrently.

## 13. Re-embedding

A new embedding profile is rolled out as a new generation. Multi-replica re-embedding orchestration is database-visible and fenced by lease ownership/fencing token so a stale replica cannot cut over after ownership changes.

## 14. Observability

Operations are observed independently from user-visible business flow:

- Micrometer metrics;
- Prometheus/Grafana;
- OpenTelemetry export;
- executor saturation and timeout outcomes;
- graph learning/lookup/promotion metrics;
- lifecycle/reconciliation/retention metrics;
- PostgreSQL/HNSW diagnostics;
- qualification artifacts.

## 15. Feature enablement philosophy

Potentially self-changing behavior defaults to off until evidence exists. In particular, adaptive graph learning/maintenance/expansion and self-optimizing policy execution are individually feature-gated.

Recommended rollout:

```text
baseline
  -> learning only
  -> maintenance
  -> shadow expansion
  -> offline/replay evaluation
  -> controlled online expansion / competition
  -> canary
  -> broader production enablement
```

## 16. Release qualification

AkmAI distinguishes implementation readiness from release qualification.

A formal release requires evidence from the integrated quality/performance/grounding qualification workflow plus target-hardware SLO validation. Controlled synthetic benchmarks are regression/release-engineering assets; they do not by themselves prove external legal/medical correctness.

See:

- [`../audit/post-v1.1-readiness-2026-10-08.md`](../audit/post-v1.1-readiness-2026-10-08.md)
- [`../operations/production-release-gates.md`](../operations/production-release-gates.md)
- [`../../benchmarks/rag-benchmark-v1/README.md`](../../benchmarks/rag-benchmark-v1/README.md)
