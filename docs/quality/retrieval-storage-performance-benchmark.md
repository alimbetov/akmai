# Retrieval Storage Performance Benchmark

Status: Phase 0 harness  
Branch: `feature/retrieval-partitioning-architecture`

## Purpose

Choose the first production retrieval physical layout from measured PostgreSQL/pgvector behavior,
not from theoretical expectations.

The benchmark compares:

1. current-style global HNSW with document/generation stored in JSONB;
2. typed global HNSW;
3. typed single heap with partial HNSW per ACL;
4. LIST-partitioned typed storage with local HNSW;
5. LIST-partitioned document-scoped exact search.

The harness is:

`RetrievalStorageLayoutBenchmarkTest`

## CI planner tier

Normal Maven verification executes the small deterministic planner contract.

Default corpus:

```text
12,000 vectors
8 dimensions
4 ACL values
100 chunks/document
```

This tier is not a latency benchmark. Its job is to prove:

- a generic prepared LIST query executes only the requested ACL child;
- the requested child can use its local ANN index;
- document-scoped exact retrieval materializes a bounded candidate set;
- results do not cross the requested ACL.

Run:

```bash
mvn -B -Dtest=RetrievalStorageLayoutBenchmarkTest test
```

## Full benchmark

The full A/B benchmark is opt-in:

```bash
AKMAI_RUN_RETRIEVAL_BENCHMARK=true \
mvn -B -Dtest=RetrievalStorageLayoutBenchmarkTest test
```

Default full tier:

```text
100,000 vectors
64 dimensions
8 ACL values
300 chunks/document
5 warmups
20 measured iterations/layout
```

The full defaults are intentionally moderate enough for engineering workstations. Production-like
million-row/1024-dimensional runs should override them.

## Environment variables

```text
AKMAI_RUN_RETRIEVAL_BENCHMARK
AKMAI_BENCHMARK_ROWS
AKMAI_BENCHMARK_DIMENSIONS
AKMAI_BENCHMARK_ACCESS_LEVELS
AKMAI_BENCHMARK_CHUNKS_PER_DOCUMENT
AKMAI_BENCHMARK_WARMUPS
AKMAI_BENCHMARK_ITERATIONS
```

Example production-scale experiment:

```bash
AKMAI_RUN_RETRIEVAL_BENCHMARK=true \
AKMAI_BENCHMARK_ROWS=1000000 \
AKMAI_BENCHMARK_DIMENSIONS=1024 \
AKMAI_BENCHMARK_ACCESS_LEVELS=8 \
AKMAI_BENCHMARK_CHUNKS_PER_DOCUMENT=300 \
AKMAI_BENCHMARK_WARMUPS=10 \
AKMAI_BENCHMARK_ITERATIONS=50 \
mvn -B -Dtest=RetrievalStorageLayoutBenchmarkTest test
```

Do not run the million-row/1024-dimensional tier in normal CI.

## Output

When the full benchmark flag is enabled, the harness writes:

```text
target/retrieval-benchmark/layout-report.json
```

For each layout it records:

- Recall@K against an exact materialized ground truth;
- p50;
- p95;
- p99;
- EXPLAIN planning time;
- EXPLAIN execution time;
- shared buffer hits;
- shared buffer reads;
- approximate rows visited;
- executed relations;
- used index names;
- full `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, SUMMARY, FORMAT JSON)`.

## Interpretation rules

### Do not select on mean latency

The primary latency comparison is p95/p99.

Retrieval is user-facing and multi-strategy fan-out magnifies long-tail latency.

### Recall is a hard dimension

A faster ANN layout is not an improvement if Recall@K falls below the accepted retrieval-quality
gate.

### LIST routing

LIST is acceptable only if repeated/generic prepared execution touches only the requested ACL
children.

The CI contract explicitly exercises:

```sql
SET plan_cache_mode = force_generic_plan;
PREPARE ...
```

This protects against validating only the first custom prepared plan.

### Partial HNSW

Partial HNSW is a mandatory control experiment because ACL cardinality is expected to be low.

If partial HNSW matches LIST on:

- recall;
- p95/p99;
- buffer locality;
- prepared-plan stability;

while substantially reducing operational complexity, vector storage may remain a typed single
heap even if relational retrieval stores use LIST.

### Document exact mode

The exact mode should be selected from estimated candidate vectors, not permanently from document
count.

The Phase 0 benchmark uses chunks-per-document as a controlled proxy. The target production
selector should use published generation `chunk_count`.

## Required experiment matrix before final DDL

Run at minimum:

```text
ACL cardinality:       1 / 2 / 4 / 8 / 16
distribution:          uniform / 80-20 skew
chunks per document:   50 / 300 / 2000
cache:                 warm + restart/cold experiment
concurrency:           1 / 4 / 16 / expected production concurrency
dimensions:            current production embedding dimensions
```

For multi-ACL search, add separate runs with authorized scope sizes:

```text
1 / 2 / 4 / 8 / 16
```

The current harness establishes the layout primitives; multi-ACL concurrent fan-out is the next
benchmark extension after the single-ACL physical comparison is validated.

## Decision gate

Do not rewrite the clean Liquibase baseline until:

1. CI planner contracts are green;
2. full benchmark runs successfully;
3. LIST vs partial-HNSW choice is measured;
4. exact/HNSW switching threshold has initial evidence;
5. Recall@K is acceptable;
6. PostgreSQL plan behavior is stable after prepared statement reuse.

The benchmark report used for the architecture decision should be committed under
`docs/quality/results/` with the hardware/database settings recorded alongside it.
