# Architecture documentation

This directory contains both current runtime contracts and historical/target design material. Do not assume every file describes the current executable path.

## Start here

- [`current-runtime-architecture.md`](current-runtime-architecture.md) — **CURRENT** end-to-end runtime architecture.
- [`adaptive-graph-runtime.md`](adaptive-graph-runtime.md) — **CURRENT** Adaptive Association Graph behavior and feature boundaries.
- [`rag-self-optimizing-platform-v1.1-technical-spec.md`](rag-self-optimizing-platform-v1.1-technical-spec.md) — **CURRENT / IMPLEMENTED** self-optimizing v1.1 engineering contract.
- [`runtime-app-parameters.md`](runtime-app-parameters.md) — runtime-mutable parameter architecture; actual defaults remain authoritative in `src/main/resources/application.yml`.

## Adaptive retrieval and memory

| Document | Classification | Purpose |
|---|---|---|
| `adaptive-chunk-graph.md` | IMPLEMENTED DESIGN / REFERENCE | Original architecture and invariants for bounded learned chunk associations. Read together with `adaptive-graph-runtime.md` for actual current runtime. |
| `adaptive-chunk-graph-calibration.md` | CURRENT CALIBRATION CONTRACT | Measurement-driven graph threshold/limit calibration. |
| `adaptive-chunk-graph-rollout.md` | CURRENT ROLLOUT CONTRACT | Shadow/canary/online rollout rules and recovery assumptions. |
| `adaptive-graph-learning-replay.md` | CURRENT EVALUATION CONTRACT | Replay and learning evaluation for adaptive graph behavior. |
| `adaptive-memory-statistical-evaluation.md` | CURRENT QUALITY CONTRACT | Statistical evaluation and promotion evidence for adaptive memory. |
| `measured-adaptive-retrieval-v1.md` | IMPLEMENTED DESIGN / REFERENCE | Measured retrieval design and scoring boundaries. |
| `self-organizing-semantic-memory.md` | IMPLEMENTED / EVOLVING | Semantic memory concepts and persistence behavior. |

## Self-optimizing platform

| Document | Classification | Purpose |
|---|---|---|
| `rag-self-optimizing-platform-v1.1-technical-spec.md` | CURRENT | Implemented v1.1 state machine, persistent memory, shadow/canary and rollout contract. |
| `rag-self-optimizing-platform-v1-gap-remediation.md` | HISTORICAL IMPLEMENTATION SPEC | Gap-remediation blueprint used to reach the current state. Useful for design rationale, not primary runtime truth. |
| `implementation-gap-closure-spec.md` | HISTORICAL IMPLEMENTATION SPEC | Earlier implementation gap closure plan. |

## Semantic intelligence

The following documents describe the semantic/concept layer and its evolution:

- `semantic-intelligence-layer.md`
- `semantic-concept-retrieval-v1.md`
- `semantic-concepts-en-v1.md`
- `semantic-domain-corpus-v2.md`
- `semantic-morphology-surfaces-v1.md`
- `semantic-phrase-expansion-v1.md`
- `rag-assurance-domain-enrichment.md`

These documents should be read as capability/design references; current lane enablement still depends on runtime configuration and the actual registered components.

## Storage, lifecycle and retention

- `chunk-lifecycle-retention.md` — generation lifecycle and payload retirement.
- `retrieval-partitioning-indexing-strategy.md` — PostgreSQL partitioning/indexing strategy.
- `retrieval-storage-greenfield-blueprint.md` — storage architecture blueprint.
- `retrieval-storage-greenfield-ddl.sql` — blueprint DDL reference; Liquibase migrations under `src/main/resources/db/changelog` are executable truth.
- `retrieval-hot-only-tombstone.md` — hot-only/tombstone lifecycle design.
- `retrieval-archive-purge-economics.md` — retention/purge economics and operational tradeoffs.

## Authority rule

When an architecture document and runtime code disagree, treat the code + Liquibase migrations + `application.yml` as authoritative and open a documentation correction in the same change set. This directory intentionally retains historical design rationale instead of rewriting history to look as if every decision was always final.
