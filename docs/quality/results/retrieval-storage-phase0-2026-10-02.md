# Retrieval Storage Phase 0 — First Valid A/B Result

Date: 2026-10-02  
Branch: `feature/retrieval-partitioning-architecture`  
Head evaluated: `93d6a1ce87b599b847498e4f0710f18fa504d353`  
GitHub Actions benchmark run: `37054166440`  
Database image: `pgvector/pgvector:pg17`

## Scope

This is the first benchmark result whose latency, recall and buffer accounting passed harness
sanity review.

Earlier runs were intentionally discarded from architectural decision-making because the harness
included JDBC connection creation in latency, compared document-exact results with a global
ground truth, and double-counted buffers across nested EXPLAIN nodes.

## Dataset

```text
rows/vectors:          50,000
dimensions:            32
access levels:         4
chunks/document:       300
topK:                  10
warmups/layout:        3
measured iterations:   10
cache state:            warm
concurrency:            1
```

This is a directional engineering benchmark, not a production sizing claim.

## Single-ACL results

| Layout | Recall@10 | p50 ms | p95 ms | EXPLAIN exec ms | top-level shared hits | Candidate relation rows |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Current JSON/global | 1.00 | 20.372 | 22.203 | 22.778 | 1715 | 50,000 |
| Typed/global HNSW | 1.00 | 1.691 | 2.080 | 0.868 | 2332 | 10 |
| Typed/partial HNSW | 1.00 | 1.061 | 1.482 | 0.596 | 1150 | 10 |
| LIST/local HNSW | 1.00 | 1.016 | 1.291 | 0.399 | 1161 | 10 |
| LIST/document exact (300 candidates) | 1.00 | 0.646 | 0.751 | 0.160 | 10 | 300 |

## Finding 1 — typed routing columns are mandatory

The current JSON/global layout did not use HNSW in the captured representative plan.

Plan shape:

```text
Seq Scan bench_vector_json (50,000 rows)
-> hash join lifecycle
-> top-N distance sort
```

The typed/global layout used:

```text
bench_typed_global_embedding_hnsw
-> lifecycle PK validation
```

On this corpus, measured p50 moved from ~20.37 ms to ~1.69 ms.

This result must not be generalized as a fixed 12x production improvement, but it validates the
architectural requirement that `document_id`, `generation`, `chunk_id` and `access_level`
must be physical typed columns rather than hot JSON predicates.

## Finding 2 — partial HNSW and LIST/local HNSW are effectively tied under custom planning

For the ordinary parameterized query on this 4-ACL corpus:

```text
partial HNSW p50: 1.061 ms
LIST HNSW p50:    1.016 ms

partial HNSW p95: 1.482 ms
LIST HNSW p95:    1.291 ms
```

Both returned Recall@10 = 1.0 and each ANN relation produced 10 candidate rows.

At this scale there is no evidence that raw single-ACL custom-plan latency alone justifies the
extra partitioning machinery.

## Finding 3 — generic prepared plans break the partial-HNSW advantage

The benchmark explicitly executes:

```sql
SET plan_cache_mode = force_generic_plan;
PREPARE ...
```

### Typed single heap + partial HNSW

Observed:

```text
execution:             3.551 ms
candidate relation:    bench_partial
candidate rows:        12,600
index used:            bench_partial_acl_document_generation
partial HNSW used:     NO
```

The generic planner cannot prove that parameterized:

```sql
access_level = $1
```

implies a specific partial-index predicate such as:

```sql
WHERE access_level = 1
```

so the ACL-specific HNSW index is unavailable.

### LIST(access_level) + local HNSW

Observed:

```text
execution:             0.454 ms
executed child:        bench_list_al_1
candidate rows:        10
index used:            bench_list_al_1_embedding_idx
unrelated children:    not executed
```

Execution-time partition pruning remains available under the generic parameterized plan, and the
remaining child can use its local HNSW index.

This is the strongest Phase 0 evidence in favor of LIST partitioning.

## Finding 4 — document-scoped exact retrieval is a real second mode

For one document containing 300 chunks:

```text
p50:                    0.646 ms
p95:                    0.751 ms
EXPLAIN execution:      0.160 ms
candidate rows:         300
index:                   document_id + generation B-tree
Recall@10 in scope:     1.0
```

The exact path correctly performs bounded B-tree candidate access followed by an exact distance
sort.

It should remain a first-class retrieval algorithm, with the production switch based on estimated
candidate chunks rather than document count alone.

## Preliminary physical-layout decision

The current evidence changes LIST partitioning from “leading candidate” to **provisional target**
for vector storage.

Reason:

1. custom-plan latency is at least competitive with partial HNSW;
2. LIST remains stable under a forced generic prepared plan;
3. partial HNSW loses the ACL-specific ANN index under that same plan;
4. relational projection/identifier/reference stores also benefit from one shared ACL partition
   topology.

This is not yet the final DDL gate.

## Remaining gates before clean-schema rewrite

Still required:

1. dimensions at the actual embedding profile (currently 1024);
2. ACL cardinality 1 / 2 / 4 / 8 / 16;
3. multi-ACL UNION ALL fan-out;
4. skewed ACL distribution;
5. chunks/document 50 / 300 / 2000;
6. concurrency 1 / 4 / 16;
7. cold/restart cache experiment;
8. projection FTS/trigram locality;
9. identifier exact/prefix/partial locality;
10. reference lookup locality.

The current result is strong enough to continue engineering around LIST as the default design, but
not strong enough to claim final production capacity or fixed speedup ratios.
