# Architecture documentation

This directory contains current architecture contracts and active target/experimental designs. Obsolete implementation plans and superseded gap-remediation specifications are removed instead of being retained beside the runtime contract.

## Start here

- [`current-runtime-architecture.md`](current-runtime-architecture.md) — **CURRENT** end-to-end runtime architecture.
- [`adaptive-graph-runtime.md`](adaptive-graph-runtime.md) — **CURRENT** Adaptive Association Graph behavior and feature boundaries.
- [`rag-self-optimizing-platform-v1.1-technical-spec.md`](rag-self-optimizing-platform-v1.1-technical-spec.md) — **CURRENT / IMPLEMENTED** self-optimizing v1.1 engineering contract.
- [`fileservice-knowledge-contract-v1.md`](fileservice-knowledge-contract-v1.md) — **CURRENT** FileService → AkmAI canonical knowledge boundary implemented in the existing ingestion/generation pipeline.
- [`async-ingestion-worker-v1.md`](async-ingestion-worker-v1.md) — **TARGET** durable asynchronous ingestion admission/queue/worker architecture with lease/fencing, retries and bounded concurrency.
- [`runtime-app-parameters.md`](runtime-app-parameters.md) — runtime-mutable parameter architecture; actual defaults remain authoritative in `src/main/resources/application.yml`.

For concrete HTTP/service request-response examples and RAG question/answer contracts, see [`../services/external-api-contracts.md`](../services/external-api-contracts.md).

## Current integration architecture

| Document | Classification | Purpose |
|---|---|---|
| [`fileservice-knowledge-contract-v1.md`](fileservice-knowledge-contract-v1.md) | CURRENT | Synchronous-first FileService → AkmAI canonical service boundary using the existing ingestion/generation pipeline, stable source identity, generation-aware result/replay and typed retrieval provenance. The canonical v1 service path is implemented; a dedicated public canonical REST endpoint is not currently exposed. |
| [`async-ingestion-worker-v1.md`](async-ingestion-worker-v1.md) | TARGET | Evolves the FileService boundary to `202 Accepted` + PostgreSQL durable ingestion jobs + bounded worker execution. Reuses the existing `KnowledgeIngestionService`; default target concurrency is three simultaneous ingestions per AkmAI instance, not fixed batches of three. |

## Adaptive retrieval and memory

| Document | Classification | Purpose |
|---|---|---|
| `adaptive-chunk-graph.md` | CURRENT REFERENCE | Core architecture and invariants for bounded learned chunk associations. Read together with `adaptive-graph-runtime.md` for executable runtime behavior. |
| `adaptive-chunk-graph-calibration.md` | CURRENT CALIBRATION CONTRACT | Measurement-driven graph threshold/limit calibration. |
| `adaptive-chunk-graph-rollout.md` | CURRENT ROLLOUT CONTRACT | Shadow/canary/online rollout rules and recovery assumptions. |
| `adaptive-graph-learning-replay.md` | CURRENT EVALUATION CONTRACT | Replay and learning evaluation for adaptive graph behavior. |
| `adaptive-memory-statistical-evaluation.md` | CURRENT QUALITY CONTRACT | Statistical evaluation and promotion evidence for adaptive memory. |
| `measured-adaptive-retrieval-v1.md` | CURRENT REFERENCE | Measured retrieval design and scoring boundaries. |
| `self-organizing-semantic-memory.md` | CURRENT / EVOLVING | Semantic memory concepts and persistence behavior. |

## Self-optimizing platform

| Document | Classification | Purpose |
|---|---|---|
| `rag-self-optimizing-platform-v1.1-technical-spec.md` | CURRENT | Implemented v1.1 state machine, persistent memory, shadow/canary and rollout contract. |

Superseded implementation-gap and remediation blueprints are not part of the active architecture set. If an old document contains a decision whose rationale must be preserved, capture that decision as an ADR instead of retaining stale implementation instructions.

## Semantic intelligence

The following documents describe the semantic/concept layer and its current evolution:

- `semantic-intelligence-layer.md`
- `semantic-concept-retrieval-v1.md`
- `semantic-concepts-en-v1.md`
- `semantic-domain-corpus-v2.md`
- `semantic-morphology-surfaces-v1.md`
- `semantic-phrase-expansion-v1.md`
- `rag-assurance-domain-enrichment.md`

Current lane enablement depends on runtime configuration and registered components. Any document in this list that no longer matches those executable paths must be updated or removed during the service/process inventory pass.

## Storage, lifecycle and retention

- `chunk-lifecycle-retention.md` — generation lifecycle and payload retirement.
- `retrieval-partitioning-indexing-strategy.md` — PostgreSQL partitioning/indexing strategy.
- `retrieval-storage-greenfield-blueprint.md` — storage architecture blueprint; keep only while it remains aligned with the implemented storage direction.
- `retrieval-storage-greenfield-ddl.sql` — blueprint DDL reference; Liquibase migrations under `src/main/resources/db/changelog` are executable truth.
- `retrieval-hot-only-tombstone.md` — hot-only/tombstone lifecycle design.
- `retrieval-archive-purge-economics.md` — retention/purge economics and operational tradeoffs.

## Lifecycle rule

Architecture documents use one of these active statuses:

- `CURRENT` — describes implemented runtime behavior;
- `TARGET` — approved future behavior not yet fully implemented;
- `EXPERIMENTAL` — evaluated behavior with no production authority yet.

A document that becomes obsolete must be updated to the current contract or removed. `HISTORICAL` is not an active architecture-document status. Historical rationale belongs in an ADR only when the decision remains useful for understanding an invariant, compatibility boundary or operational constraint.

## Authority rule

When an architecture document and runtime code disagree, treat code + Liquibase migrations + `application.yml` as authoritative, then correct the documentation in the same change set. The long-term target is that active documentation and executable behavior do not disagree.
