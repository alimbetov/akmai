# AkmAI documentation

This directory is the documentation entry point for the current AkmAI codebase.

Last synchronized against executable code baseline: `main@2b52dbade51987ddba61e8decacb09f119614c19` on 2026-10-09.

## Documentation contract

AkmAI documentation must describe the current executable system. The repository must not accumulate stale implementation plans, superseded gap-remediation specifications or duplicate architecture descriptions.

Use this precedence when documents disagree:

1. executable code, database migrations and `src/main/resources/application.yml`;
2. current-state documents linked from this page;
3. current audit/readiness documents;
4. operational and quality contracts.

If a document no longer matches the current runtime, it must be updated in the same change set or removed. Historical implementation plans must not remain in the active documentation tree merely as background material. Preserve historical rationale only when it still has architectural value, and then convert it into an explicit ADR with the decision, context and consequences rather than keeping an obsolete implementation specification.

## Current-state documentation

| Topic | Authoritative document |
|---|---|
| Product status and quick start | [`../README.md`](../README.md) |
| Runtime architecture | [`architecture/current-runtime-architecture.md`](architecture/current-runtime-architecture.md) |
| Adaptive Association Graph runtime | [`architecture/adaptive-graph-runtime.md`](architecture/adaptive-graph-runtime.md) |
| Runtime feature switches | [`architecture/runtime-app-parameters.md`](architecture/runtime-app-parameters.md) and `application.yml` |
| External/integration contracts and RAG question/answer examples | [`services/external-api-contracts.md`](services/external-api-contracts.md) |
| Knowledge ingestion behavior | [`services/knowledge-ingestion.md`](services/knowledge-ingestion.md) |
| FileService → AkmAI knowledge contract v1 | [`architecture/fileservice-knowledge-contract-v1.md`](architecture/fileservice-knowledge-contract-v1.md) |
| Self-Optimizing RAG v1.1 | [`architecture/rag-self-optimizing-platform-v1.1-technical-spec.md`](architecture/rag-self-optimizing-platform-v1.1-technical-spec.md) |
| Release readiness | [`audit/post-v1.1-readiness-2026-10-08.md`](audit/post-v1.1-readiness-2026-10-08.md) |
| Production gates | [`operations/production-release-gates.md`](operations/production-release-gates.md) |
| Benchmark contract | [`../benchmarks/rag-benchmark-v1/README.md`](../benchmarks/rag-benchmark-v1/README.md) |

## Integration contract entry point

For an upstream FileService or API consumer, start with [`services/external-api-contracts.md`](services/external-api-contracts.md). It explicitly separates:

- HTTP endpoints that are actually published by controllers today;
- the implemented service-level `CanonicalKnowledgeDocument -> KnowledgeIngestionResult` FileService boundary;
- expected request/response JSON shapes and identity rules;
- `POST /api/rag/ask` question contract;
- `RagResponse` answer, source and canonical-block provenance contract;
- common API errors and idempotency semantics.

A service-level Java contract must not be treated as a public REST endpoint unless a controller exposes it.

## Directory map

### `architecture/`

Current and explicitly marked target architecture, storage and retrieval contracts. Start with [`architecture/README.md`](architecture/README.md).

Major areas:

- current RAG runtime and adaptive graph;
- FileService → AkmAI canonical knowledge-ingestion contract;
- self-optimizing retrieval and query memory;
- semantic concept retrieval and semantic intelligence;
- storage partitioning, lifecycle, retention and purge economics;
- graph calibration, replay and statistical evaluation.

### `services/`

Current service/process behavior and external/integration contracts. Start with [`services/README.md`](services/README.md).

For API and FileService integration use [`services/external-api-contracts.md`](services/external-api-contracts.md).

### `audit/`

Current audit snapshots, defect ledgers and remediation evidence. Start with [`audit/README.md`](audit/README.md).

The open GitHub issues #36-#41 are historical audit trackers. Their GitHub state is still open, while most runtime remediations are already present in `main`; current closure/evidence status is maintained in the post-v1.1 readiness audit. Once an audit document is fully superseded and contains no unique evidence needed for traceability, remove it rather than retaining a stale duplicate.

### `operations/`

Deployment, security, disaster recovery, PostgreSQL/HNSW diagnostics and release gates. Start with [`operations/README.md`](operations/README.md).

### `quality/`

RAG assurance contracts, retrieval benchmarks, storage-performance benchmarks and retained result artifacts. Start with [`quality/README.md`](quality/README.md).

## Core runtime model

```text
INGEST
  canonical content
    -> normalize / structure
    -> semantic parent-child chunking
    -> identifiers + references + semantic annotations
    -> lexical/search projection + embeddings
    -> atomic generation publication

ASK
  question
    -> query analysis
    -> retrieval plan
    -> vector / lexical / identifier / reference / concept lanes
    -> result fusion
    -> reranking
    -> structural knowledge expansion
    -> adaptive graph shadow/online expansion when enabled
    -> competitive admission
    -> authority/lifecycle filtering
    -> parent expansion + diversity filter
    -> context budget + published-context revalidation
    -> local generation
    -> citation validation
    -> deterministic grounding
    -> optional semantic grounding
    -> learning/utility observations

OPERATE / LEARN
  metrics + tracing + lifecycle workers
    -> adaptive graph maintenance
    -> persistent query memory
    -> offline/shadow/canary policy evaluation
    -> release qualification
```

## Non-negotiable runtime invariants

- ACL is a pre-routing boundary, not a post-filter.
- Only eligible published generations may enter final context.
- TTL expiry is synchronously fenced during retrieval.
- Exact identifiers and explicit document references retain higher authority than learned associations.
- Adaptive memory is derived retrieval memory, never authoritative knowledge.
- Graph-origin evidence does not directly reinforce itself as a normal learning source.
- All online expansion is bounded by seed, neighbour, candidate and context budgets.
- Embedding spaces are versioned and never mixed across profiles.
- Non-local startup is fail-closed for unsafe security or default-secret configuration.
- Release qualification is evidence-based; a green compile/unit-test run is not production qualification.

## Documentation maintenance rule

When runtime behavior changes, update in the same PR:

1. `README.md` if user-visible capability/status changes;
2. the relevant current-state document under `docs/`;
3. `application.yml` documentation when configuration changes;
4. release/quality contracts when a gate or metric changes;
5. the audit/readiness document if the change closes or reclassifies a blocker.

Every maintained process/business-logic document must state purpose, normal flow, negative/failure paths, invariants and corresponding tests. Transaction, retry/idempotency, timeout/cancellation and concurrency/locking/fencing semantics must be documented whenever they are part of the process contract.

Do not add a new design or implementation document without assigning it an explicit lifecycle status. Allowed active statuses are `CURRENT`, `TARGET` and `EXPERIMENTAL`. When a `TARGET` or `EXPERIMENTAL` document is implemented or abandoned, update it to the current contract or remove it. Use ADRs only for decisions whose historical rationale remains operationally or architecturally relevant.
