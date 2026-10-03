# Retrieval Storage Phase 0 — Physical Layout Decision

Date: 2026-10-03  
Branch: `feature/retrieval-partitioning-architecture`  
PostgreSQL: 17 + pgvector  
Embedding dimensions tested: 1024

## Decision

The first production retrieval schema will use:

```text
LIST(access_level)
```

for all large retrieval/data-plane relations:

- `knowledge_search_projection`;
- `document_identifier`;
- `knowledge_reference_target`;
- `knowledge_reference_edge`;
- `knowledge_document_vector_generation`;
- every dynamic `akmai_vector.<profile>` table.

Control-plane relations remain unpartitioned:

- `knowledge_document_lifecycle`;
- `knowledge_document_generation`;
- embedding profile/runtime/migration journals;
- ingestion idempotency state.

This is now the final physical-layout decision for the greenfield baseline.

## Why LIST is final

### 1. Typed columns are mandatory

The earlier A/B run showed that the current JSON-routed layout can fall to a full vector-table scan,
while typed `access_level/document_id/generation/chunk_id` allows the intended ANN/index plans.

Therefore JSONB remains metadata only. It is not a routing or generation identity mechanism.

### 2. Partial HNSW is not stable enough under generic prepared plans

Under an ordinary custom plan, partial-HNSW and LIST/local-HNSW were close.

Under:

```sql
SET plan_cache_mode = force_generic_plan;
```

the parameterized predicate:

```sql
access_level = $1
```

could no longer prove a specific partial-index predicate such as:

```sql
WHERE access_level = 1
```

The partial HNSW graph disappeared from the plan.

LIST retained:

```text
execution-time partition pruning
-> one ACL child
-> child-local HNSW
```

This plan stability is more important than a small custom-plan microbenchmark difference.

### 3. One partition topology serves every retrieval strategy

LIST gives the same routing model to:

```text
ANN
FTS
trigram
identifier
reference traversal
canonical lookup
adjacency
retention/reconciliation cleanup
```

A vector-only partial-index design does not provide this common data-plane topology.

## 1024-dimensional ACL matrix

The decision-matrix run used:

```text
dimensions:          1024
rows per ACL:        1200
chunks/document:     300
topK:                10
warm cache
concurrency sweep:   1 / 4 / 16
```

Each scalar ACL branch touched only its authorized LIST child and used that child's local HNSW.

Baseline branch policy was:

```text
branch candidate limit = final K = 10
hnsw.ef_search = 40
```

### Widest-scope baseline

| Physical ACL count / scope | Recall@10 | p50 ms | p95 ms | C16 p99 ms |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 1.0 | 1.93 | 2.05 | 25.22 |
| 2 | 1.0 | 3.76 | 4.14 | 41.88 |
| 4 | 0.8 | 6.06 | 6.14 | 78.61 |
| 8 | 0.9 | 10.57 | 11.40 | 116.13 |
| 16 | 0.8 | 12.03 | 12.53 | 126.56 |

These latency values are GitHub-hosted-runner measurements and are not production capacity claims.

The important planner result is that multi-ACL cost grows with the number of ANN branches while
partition routing remains correct.

## ANN quality finding

The 1024d run exposed a query-strategy issue that is separate from partitioning:

```text
one approximate local topK per ACL
-> UNION ALL
-> global topK
```

does not guarantee exact global Recall@K because each local HNSW result is approximate.

At 4+ ACL scopes the baseline `K=10 / ef_search=40` lost exact ground-truth hits.

The harness now tests:

```text
branch candidate multiplier: 1 / 2 / 4
hnsw.ef_search:             40 / 80 / 120
```

and verifies Recall@10 against an exact materialized ground truth.

Full Recall@10 was recoverable in the tested corpus.

## Important tuning caveat

On the small 1200-row-per-ACL corpus, increasing branch candidate count or `ef_search` caused a
large latency cliff.

That result must not be turned into a production constant yet.

The likely cause is a planner transition on the small child relation: once more candidates are
requested, exact/scan-style work can become cheaper than the ANN path.

Therefore:

- LIST is final;
- branch oversampling is configurable;
- `ef_search` is configurable;
- the exact production fan-out policy still requires a larger per-partition corpus and plan
  capture.

This does **not** block the greenfield DDL because it changes query policy, not physical identity or
partition keys.

## Final storage invariants

The clean schema therefore adopts these invariants:

1. `access_level` is a typed physical column on every retrieval row;
2. `document_id`, `generation`, and `chunk_id` are typed vector columns;
3. generation identity is protected by:
   `UNIQUE(document_id, generation, access_level)`;
4. retrieval rows reference that generation ACL identity by FK where practical;
5. no retrieval parent has a DEFAULT partition;
6. unknown/unprovisioned ACL writes fail closed;
7. vector/profile child indexes are local HNSW indexes;
8. downstream hits preserve exact ACL so fusion/reference/adjacency reads do not re-fan-out;
9. document-scoped selective vector retrieval remains an exact B-tree candidate path;
10. multi-ACL ANN uses scalar ACL branches, never array-valued routing predicates.

## Liquibase consequence

Because no database has been deployed, the project will not preserve the current historical ALTER
chain.

A new executable greenfield changelog is being introduced under:

`db/changelog/greenfield/`

It creates the final schema directly.

The existing master changelog remains temporarily active only until Java persistence/retrieval
repositories are adapted to the new row identities. Once those changes are green, the master
entrypoint can switch to the greenfield baseline without a data migration/cutover layer.
