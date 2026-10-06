# Self-Organizing Semantic Memory

## Status

This design evolves the existing Adaptive Graph. It is intentionally **not** a RAPTOR implementation and does not introduce a second clustering or hierarchical retrieval engine.

The core rule is:

> Semantic similarity proposes an association; usage evidence promotes it; lifecycle maintenance forgets associations that do not prove useful.

## Goals

- Discover strong semantic relationships during ingestion without exposing them immediately to retrieval.
- Reuse the existing citation/query-driven Adaptive Graph learning loop instead of creating a second learning subsystem.
- Preserve ACL, generation, lifecycle and graph-version isolation.
- Keep ingestion publication independent from optional semantic-memory enrichment.
- Retain a stable semantic prior that can later support graph-community discovery.
- Keep generated summaries non-authoritative: final evidence and citations must resolve to canonical source chunks.

## Phase 1 — Ingestion semantic linking

After a generation is committed as `PUBLISHED` and its lifecycle is `READY/ACTIVE`, each new vector is used as an ANN query against already published vectors in the same ACL scope.

The linker applies bounded gates:

1. ANN `topK` candidate limit.
2. Minimum cosine similarity.
3. Same ACL is mandatory.
4. Same language is enabled by default.
5. Self-links are rejected.
6. Duplicate pairs are canonicalized inside the ingestion batch.
7. Maximum semantic edges per source chunk is enforced.
8. Both source and target must still be `ACTIVE/PUBLISHED` when the association is written.

Semantic linking is post-commit and fail-open. A linking failure must not roll back or invalidate an otherwise successful document publication. Re-running publication/idempotent recovery may safely retry linking.

### Logical vs physical symmetry

`SEMANTIC_SIMILARITY` is logically symmetric. The current Adaptive Graph storage is optimized around source-local lookup and is partitioned/indexed by `source_*`, so the relation is physically stored as two synchronized directed rows:

```text
A -> B
B -> A
```

Both rows are written atomically under deterministic node locks. We intentionally do not migrate the graph to a single undirected physical row, because that would make the hot retrieval lookup require source/target disjunctions and weaken existing partition locality.

## Two independent scores

Semantic similarity and learned graph utility are different signals and must never share the same field.

- `semantic_similarity`: ingestion-time semantic prior. It represents embedding proximity and remains available for future community discovery.
- `weight`: learned Adaptive Graph utility. It is recalculated from actual query/citation evidence and freshness.

A semantic seed therefore enters the graph as:

```text
band = CANDIDATE
weight = 0
semantic_similarity = cosine_similarity
query/citation evidence = 0
```

A high embedding similarity alone cannot make an edge `WARM` or `HOT`.

## Phase 2 — Adaptive strengthening and decay

No parallel reinforcement subsystem is introduced. The existing Adaptive Graph machinery remains authoritative for promotion and decay.

When two chunks prove useful together, existing query/citation telemetry increases evidence. Maintenance then moves associations through the existing lifecycle:

```text
CANDIDATE -> WARM -> HOT
                  \
                   -> CANDIDATE -> DECAYED
```

Live graph expansion only reads eligible `WARM`/`HOT` associations. Ingestion-created `CANDIDATE` edges are therefore invisible to online graph retrieval until behavioral evidence promotes them.

This protects retrieval from semantic false positives and embedding-model artifacts.

## Phase 3 — Graph community summaries (future)

Community summaries are deliberately not implemented in v1.

When enough production evidence exists, a separate bounded maintenance job may discover stable graph communities using both:

- semantic prior (`semantic_similarity`), and
- learned graph utility (`weight`, band, citations, distinct query support, stability).

A community may then produce a `SUMMARY_NODE` used only for routing/navigation. It must never become authoritative evidence.

Expected flow:

```text
query
  -> summary/community routing
  -> member chunk expansion
  -> canonical published chunks
  -> rerank/fusion
  -> original-chunk citations
```

No answer should cite a generated community summary as the source of truth.

## Security and lifecycle invariants

1. Cross-ACL semantic associations are forbidden.
2. Source and target identities include `accessLevel`, `documentId`, `generation`, and `chunkId`.
3. Linking only targets published, active generations.
4. Existing graph-version isolation remains in force.
5. Archived/retired generations cannot be newly linked.
6. Retrieval still revalidates canonical published projections before graph candidates enter context.
7. Semantic-memory enrichment cannot change publication success/failure semantics.

## Runtime control

The feature is disabled by default:

```text
akmai.semantic-memory.ingestion-linking-enabled = false
```

Initial tuning defaults are intentionally conservative:

```text
topK = 16
maxEdgesPerChunk = 6
minSimilarity = 0.90
sameLanguageOnly = true
```

These are starting values, not permanent quality thresholds. Production activation should be gated by a golden set and telemetry for edge volume, promotion rate, citation assist rate, stale-edge rate and latency/cost impact.

## Non-goals for v1

- RAPTOR-compatible recursive clustering.
- LLM-generated summary nodes.
- Cross-ACL linking.
- Cross-language linking by default.
- Direct retrieval from semantic `CANDIDATE` edges.
- Using cosine similarity as Adaptive Graph utility weight.
- Treating generated summaries as citation evidence.
