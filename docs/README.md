# AkmAI documentation

This directory is the documentation entry point for the current AkmAI codebase.

Last synchronized against: `main@33ebbe469d994fe60c227972b1d7f730ce6cf0ba` on 2026-10-08.

## Documentation contract

AkmAI has accumulated architecture proposals, implementation specifications, audit ledgers, runbooks and benchmark contracts. They do not all have the same authority.

Use this precedence when documents disagree:

1. executable code, database migrations and `src/main/resources/application.yml`;
2. current-state documents linked from this page;
3. current audit/readiness documents;
4. operational and quality contracts;
5. design specifications and historical remediation documents.

A design document may describe intent that has already evolved in code. Historical audit documents remain useful evidence but must not be interpreted as the current runtime contract without checking the current-state documentation.

## Current-state documentation

| Topic | Authoritative document |
|---|---|
| Product status and quick start | [`../README.md`](../README.md) |
| Runtime architecture | [`architecture/current-runtime-architecture.md`](architecture/current-runtime-architecture.md) |
| Adaptive Association Graph runtime | [`architecture/adaptive-graph-runtime.md`](architecture/adaptive-graph-runtime.md) |
| Runtime feature switches | [`architecture/runtime-app-parameters.md`](architecture/runtime-app-parameters.md) and `application.yml` |
| Self-Optimizing RAG v1.1 | [`architecture/rag-self-optimizing-platform-v1.1-technical-spec.md`](architecture/rag-self-optimizing-platform-v1.1-technical-spec.md) |
| Release readiness | [`audit/post-v1.1-readiness-2026-10-08.md`](audit/post-v1.1-readiness-2026-10-08.md) |
| Production gates | [`operations/production-release-gates.md`](operations/production-release-gates.md) |
| Benchmark contract | [`../benchmarks/rag-benchmark-v1/README.md`](../benchmarks/rag-benchmark-v1/README.md) |

## Directory map

### `architecture/`

Architecture, storage and retrieval design. Start with [`architecture/README.md`](architecture/README.md). The directory includes both current contracts and older design/implementation specifications.

Major areas:

- current RAG runtime and adaptive graph;
- self-optimizing retrieval and query memory;
- semantic concept retrieval and semantic intelligence;
- storage partitioning, lifecycle, retention and purge economics;
- graph calibration, replay and statistical evaluation.

### `audit/`

Audit snapshots, defect ledgers and remediation specifications. Start with [`audit/README.md`](audit/README.md).

The open GitHub issues #36-#41 are historical audit trackers. Their GitHub state is still open, while most runtime remediations are already present in `main`; current closure/evidence status is maintained in the post-v1.1 readiness audit.

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

New architecture proposals should explicitly state one of: `CURRENT`, `TARGET`, `EXPERIMENTAL`, `HISTORICAL`.
