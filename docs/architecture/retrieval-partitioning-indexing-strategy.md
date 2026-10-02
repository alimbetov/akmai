# AKMAI — Retrieval Partitioning & Indexing Strategy

Status: strategic architecture review  
Branch: `feature/retrieval-partitioning-architecture`  
Base: `7ecc91124bfc2716d46a5e66e6a225b1687189fc`  
Target database: PostgreSQL 17 + pgvector  
Scope: retrieval storage, query planning, indexing, migration/cutover, performance gates

## 1. Executive decision

AKMAI should optimize retrieval around the fact that `access_level` is now a mandatory,
server-authorized security dimension on every retrieval request.

The strategic direction is:

1. move hot routing/filter/join attributes out of JSONB into typed columns;
2. benchmark two ACL-local physical layouts before locking the final vector layout:
   - LIST partitioning by `access_level` with local HNSW;
   - a single typed vector heap with one partial HNSW index per access level;
3. prefer LIST partitioning for projection / identifier / reference stores once corpus scale
   makes heap and index locality material;
4. preserve lifecycle + generation tables as an unpartitioned control plane;
5. add a mandatory exact vector path for highly selective document-scoped retrieval;
6. stop using array-valued `= ANY (?)` as the partition-routing predicate;
7. preserve generation publication fencing and use
   `knowledge_document_generation.access_level` as the immutable ACL source of truth for
   backfill and migration;
8. make the final choice benchmark-driven, with plan-shape and retrieval-quality gates.

The important refinement compared with the first design is that LIST partitioning is the
leading candidate, not a dogma. With a genuinely small and stable ACL cardinality, partial
HNSW indexes can provide physically separate ANN graphs with less schema/migration complexity.
The benchmark must prove whether heap locality and general index locality justify full table
partitioning.

## 2. Non-negotiable invariants

The optimization must not weaken existing correctness guarantees.

### 2.1 Authorization

Every published retrieval read must still require a non-empty authorized access scope.

### 2.2 Generation fencing

A row is retrievable only when:

```text
row.document_id = lifecycle.document_id
row.generation = lifecycle.published_generation
row.access_level = lifecycle.access_level
lifecycle.retention_status = ACTIVE
```

A publication switch stays O(1) at the lifecycle row. No design may require mass-updating
all chunks/vectors on publication.

### 2.3 Immutable generation ACL

The ACL attached to one generation is immutable.

Migration/backfill must derive ACL from:

```text
knowledge_document_generation(document_id, generation, access_level)
```

not from the current lifecycle row, because the lifecycle row represents the currently
published generation and can have a different ACL from retired/staging generations.

### 2.4 No hidden fallback partition

No retrieval parent should have a DEFAULT partition. Unknown ACL values must fail visibly.

## 3. Current retrieval storage inventory

### 3.1 `knowledge_search_projection`

Current responsibilities:

- canonical chunk projection;
- RU/EN FTS;
- simple FTS fallback;
- KK/ZH trigram search;
- canonical chunk lookup;
- adjacency/context expansion.

Current hot indexes include:

- PK `(document_id, generation, chunk_id)`;
- unique `(document_id, generation, chunk_index)`;
- B-tree document/generation/language paths;
- GIN simple FTS;
- GIN RU FTS;
- GIN EN FTS;
- trigram GIN on text;
- trigram GIN on section path.

Current security filtering is performed through a lifecycle join. The projection row has no
physical `access_level`, so the projection relation itself cannot currently be pruned by ACL.

### 3.2 `document_identifier`

Current retrieval modes:

- exact value;
- type + exact value;
- type + prefix;
- type + partial/trigram.

Current ACL filtering is also lifecycle-join-only. The table has no physical ACL routing column.

### 3.3 `knowledge_reference_edge` / `knowledge_reference_target`

Current hot path is document + generation scoped and joins edge to target by normalized reference
identity. ACL is checked through lifecycle only.

### 3.4 dynamic `akmai_vector.<profile_table>`

Current physical columns:

```text
id UUID
content TEXT
metadata JSONB
embedding VECTOR(N)
```

Routing keys are currently extracted from JSONB:

```text
akmaiDocumentId
akmaiGeneration
akmaiChunkId
akmaiEmbeddingProfileId
```

The vector table has one HNSW index over all rows in the embedding profile.

This is the biggest performance opportunity because:

- ANN scans a shared graph;
- filtering is applied after ANN candidate traversal;
- document/generation values require JSON extraction/casts;
- ACL exists only in lifecycle, so ANN cannot route by ACL before the graph traversal.

### 3.5 control-plane tables

These must remain unpartitioned:

- `knowledge_document_lifecycle`;
- `knowledge_document_generation`;
- `knowledge_embedding_runtime`;
- `knowledge_embedding_profile`;
- embedding migration journal.

They are coordination/state tables, not retrieval corpora.

### 3.6 vector manifest

`knowledge_document_vector_generation` has one row per vector identity and is large, but is
primarily a reconciliation/cleanup manifest, not a search relation.

Phase 1 should add `access_level` to the manifest so cleanup can route vector deletion directly.
Partitioning the manifest is optional and should be justified by cleanup benchmarks, not retrieval
latency.

## 4. Main performance problems in the current design

### 4.1 Filtered ANN happens too late

Current vector search scans one HNSW graph and only afterwards validates lifecycle/ACL.

This creates:

- unnecessary graph traversal;
- reduced filtered recall;
- more iterative HNSW scanning;
- CPU and memory pressure from candidates that can never be returned.

### 4.2 Hot keys are hidden in JSONB

JSONB is useful for extensible metadata, but routing keys should not live there.

The following must become typed physical columns in storage v2:

- `access_level`;
- `document_id`;
- `generation`;
- `chunk_id`.

`embedding_profile_id` does not need to be a hot predicate in a per-profile vector table.

### 4.3 Array ACL predicates are hostile to deterministic pruning

The current repositories use PostgreSQL array binding:

```sql
l.access_level = ANY (?)
```

For partition routing, AKMAI must not rely on an array parameter as the partition-key predicate.

The v2 SQL builder should generate scalar routing branches:

```sql
WHERE access_level = ?
```

and combine multiple ACL branches explicitly.

### 4.4 Document-scoped retrieval does not exploit selectivity

Identifier/reference retrieval can reduce a semantic query to a small document set.

When the resulting candidate set is hundreds or a few thousand vectors, traversing a large HNSW
graph is often unnecessary. The database should first use B-tree document/generation access and
then compute exact vector distances over the bounded candidate set.

## 5. Partition-key alternatives

| Strategy | Global ANN | Document scoped | Lexical | ACL isolation | Complexity | Decision |
| --- | --- | --- | --- | --- | --- | --- |
| LIST `access_level` | strong | strong | strong | strong | medium | preferred candidate |
| partial indexes by ACL | strong for few ACLs | strong | medium | index-level only | lower | benchmark against LIST |
| HASH `document_id` | requires all partitions | strong | poor for global | none | high | reject |
| LIST language | poor for vector/unknown language | neutral | strong | none | high | reject |
| LIST domain | requires fan-out | neutral | mixed | none | high | reject |
| RANGE time | global retrieval scans history | poor | poor | none | medium | reject for corpus |
| ACL -> document hash subpartition | N ANN scans inside one ACL | strong | mixed | strong | very high | only future scale experiment |

The partitioning key must be known before expensive retrieval starts. `access_level` is the only
current mandatory attribute satisfying that rule for every retrieval strategy.

## 6. Vector physical-layout decision: LIST vs partial HNSW

This is the most important benchmark decision.

### 6.1 Option A — LIST partition by ACL

```sql
CREATE TABLE akmai_vector.embedding_x_v2 (
    access_level bigint NOT NULL,
    document_id varchar(100) NOT NULL,
    generation bigint NOT NULL,
    chunk_id varchar(100) NOT NULL,
    id uuid NOT NULL,
    content text NOT NULL,
    metadata jsonb NOT NULL,
    embedding vector(1024) NOT NULL,
    PRIMARY KEY (access_level, id),
    CHECK (access_level > 0)
) PARTITION BY LIST (access_level);
```

Each child gets:

- local HNSW;
- local B-tree `(document_id, generation)`.

Benefits:

- physical heap locality;
- isolated ANN graph;
- local statistics;
- local indexes stay smaller;
- ACL child can be reindexed/maintained independently.

Costs:

- ACL provisioning;
- partition-aware constraints;
- query fan-out for multi-ACL scopes;
- more DDL/index lifecycle operations.

### 6.2 Option B — one heap + partial HNSW per ACL

Example:

```sql
CREATE INDEX embedding_x_acl_1_hnsw
ON akmai_vector.embedding_x_v2
USING hnsw (embedding vector_cosine_ops)
WHERE access_level = 1;
```

This also creates an ACL-specific HNSW graph.

Benefits:

- simpler physical schema;
- no partitioned PK/FK restructuring;
- no partition DDL;
- attractive if there are only a few stable ACL values.

Costs:

- heap pages remain shared across ACLs;
- partial-index eligibility is planner-sensitive;
- parameterized predicates and generic prepared plans require special care;
- FTS/identifier/reference stores would still need another locality strategy;
- multi-ACL search still requires one ANN branch per ACL.

### 6.3 Decision rule

Do not decide from theory.

Benchmark both with:

- 1, 2, 4, 8, 16 active ACL values;
- uniform ACL distribution;
- strongly skewed distribution (large public/level-1 corpus + small restricted levels);
- warm-cache and realistic concurrent load;
- pgJDBC prepared-statement behavior after server prepare activates.

LIST becomes the default if it materially improves p95/p99, buffer locality, index residency,
maintenance isolation, or plan stability.

Partial HNSW remains acceptable if it matches LIST performance/recall at materially lower
operational complexity.

## 7. Proposed storage-v2 domain model

Introduce an immutable value object conceptually equivalent to:

```text
GenerationIdentity {
    documentId
    generation
    accessLevel
}
```

Publication knows this identity from `knowledge_document_generation` before writing any physical
retrieval state.

Do not recover ACL from free-form metadata.

All stores should receive the generation identity explicitly.

## 8. Projection storage v2

Target parent:

```sql
CREATE TABLE knowledge_search_projection_v2 (
    access_level bigint NOT NULL,
    document_id varchar(100) NOT NULL,
    generation bigint NOT NULL,
    chunk_id varchar(100) NOT NULL,
    parent_chunk_id varchar(100),
    chunk_index integer NOT NULL,
    text_content text NOT NULL,
    embedding_text text NOT NULL,
    language varchar(32) NOT NULL,
    domain varchar(50) NOT NULL,
    section_path text,
    identifiers_json jsonb NOT NULL,
    references_json jsonb NOT NULL,
    metadata_json jsonb NOT NULL,
    projection_version integer NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (... ) STORED,
    search_vector_ru tsvector GENERATED ALWAYS AS (... ) STORED,
    search_vector_en tsvector GENERATED ALWAYS AS (... ) STORED,
    PRIMARY KEY (access_level, document_id, generation, chunk_id)
) PARTITION BY LIST (access_level);
```

Recommended local B-tree indexes:

```text
(document_id, generation, chunk_id)
(document_id, generation, chunk_index)
```

Recommended local language-specific GIN indexes:

```sql
... USING GIN (search_vector_ru) WHERE language = 'ru';
... USING GIN (search_vector_en) WHERE language = 'en';
```

For KK/ZH trigram:

```sql
... USING GIN (lower(text_content) gin_trgm_ops)
WHERE language IN ('kk', 'zh');

... USING GIN (lower(coalesce(section_path, '')) gin_trgm_ops)
WHERE language IN ('kk', 'zh');
```

The simple fallback index remains until benchmark proves it unnecessary.

## 9. Identifier storage v2

Target identity:

```text
(access_level, id)
```

Business uniqueness:

```text
(access_level, document_id, generation, chunk_id, identifier_type, normalized_value)
```

Local indexes to benchmark:

```text
(identifier_type, normalized_value)
(normalized_value)
(identifier_type, normalized_value text_pattern_ops)
GIN(normalized_value gin_trgm_ops)
```

Do not partition identifiers by `created_at` for the retrieval corpus. Identifier lookup has no
date predicate and would fan out across time partitions.

## 10. Reference storage v2

Both edge and target receive physical `access_level`.

Reference joins must include:

```sql
e.access_level = ?
AND t.access_level = e.access_level
```

Local indexes:

```text
edge:
(document_id, generation, source_chunk_id, reference_type, canonical_value)

target:
(document_id, generation, reference_type, canonical_value)
```

Keep generation fencing against lifecycle.

## 11. Generation-level integrity

Add a redundant unique key specifically for security integrity:

```sql
UNIQUE (document_id, generation, access_level)
```

on `knowledge_document_generation`.

Then projection / identifier / reference stores should use:

```text
FOREIGN KEY (document_id, generation, access_level)
REFERENCES knowledge_document_generation(document_id, generation, access_level)
```

This catches an incorrect ACL at write time.

For dynamic vector tables, benchmark the FK ingestion overhead before making it mandatory. At
minimum, application writes must be generation-identity-driven and reconciliation must verify ACL
consistency.

## 12. Global vector query

For one ACL:

```sql
SELECT ...
FROM akmai_vector.embedding_x_v2 v
JOIN knowledge_document_lifecycle l
  ON l.document_id = v.document_id
 AND l.published_generation = v.generation
 AND l.access_level = v.access_level
WHERE v.access_level = ?
  AND l.retention_status = 'ACTIVE'
ORDER BY v.embedding <=> ?
LIMIT ?;
```

The distance threshold should be applied outside an ANN materialized candidate query when required
by the pgvector execution pattern, so the ANN index can perform nearest-neighbor ordering first.

## 13. Multi-ACL vector query

Generate one scalar branch per authorized ACL:

```sql
SELECT *
FROM (
    (
        SELECT ..., v.embedding <=> ? AS distance
        FROM akmai_vector.embedding_x_v2 v
        JOIN knowledge_document_lifecycle l ...
        WHERE v.access_level = ?
        ORDER BY v.embedding <=> ?
        LIMIT ?
    )
    UNION ALL
    (
        SELECT ..., v.embedding <=> ? AS distance
        FROM akmai_vector.embedding_x_v2 v
        JOIN knowledge_document_lifecycle l ...
        WHERE v.access_level = ?
        ORDER BY v.embedding <=> ?
        LIMIT ?
    )
) candidate
ORDER BY distance
LIMIT ?;
```

Taking at least global `topK` from every ACL branch is sufficient for constructing the final
global topK.

The benchmark must measure fan-out at ACL scope sizes:

```text
1 / 2 / 4 / 8 / 16
```

and under concurrency.

Do not assume N HNSW scans scale linearly.

## 14. Prepared statements are part of the benchmark

AKMAI uses JDBC prepared statements.

The performance harness must execute each query repeatedly enough to cover pgJDBC server-side
prepare behavior and PostgreSQL generic/custom plan decisions.

A plan that looks correct on the first execution but loses the intended index/pruning behavior
after statement reuse is a production regression.

## 15. Document-scoped exact vector path

When identifier/reference retrieval produces a selective document set, use a different algorithm.

Logical plan:

```text
authorized published documents
        |
        v
access partition
        |
(document_id, generation) B-tree
        |
bounded vector rows
        |
exact cosine distance
        |
topK
```

Candidate SQL shape:

```sql
WITH authorized_docs AS MATERIALIZED (
    SELECT document_id, published_generation AS generation, access_level
    FROM knowledge_document_lifecycle
    WHERE retention_status = 'ACTIVE'
      AND access_level = ?
      AND document_id = ANY (?)
),
candidates AS MATERIALIZED (
    SELECT v.*
    FROM akmai_vector.embedding_x_v2 v
    JOIN authorized_docs d
      ON d.document_id = v.document_id
     AND d.generation = v.generation
     AND d.access_level = v.access_level
    WHERE v.access_level = ?
)
SELECT ..., embedding <=> ? AS distance
FROM candidates
ORDER BY distance
LIMIT ?;
```

The exact/HNSW switch must be benchmark-driven.

Initial configuration can use a conservative document-count threshold, but long-term selection
should use an estimated candidate-chunk count rather than only number of documents.

## 16. HNSW configuration strategy

Current AKMAI already enables:

```sql
SET LOCAL hnsw.iterative_scan = strict_order;
```

Keep HNSW controls configurable:

- `hnsw.ef_search`;
- `hnsw.max_scan_tuples`;
- `hnsw.scan_mem_multiplier`;
- iterative scan mode.

Do not globally increase these values before ACL-local graphs are benchmarked. Partitioning may
reduce the amount of iterative scanning needed.

Also benchmark an optional `halfvec` experiment only after the v2 baseline is stable. Reduced
index memory may improve cache residency but changes numerical precision and requires a separate
Recall@K gate.

## 17. Planner settings

Do not globally enable `enable_partitionwise_join`.

It can increase planning cost and multiply `work_mem` consumers with the number of partitions.

Do not globally disable parallel query either.

Benchmark:

- no parallel workers;
- planner default;
- bounded parallel workers;

for multi-ACL fan-out.

The production setting should follow measured concurrency behavior.

## 18. Partition provisioning

Create a dedicated admin schema and function family:

```text
akmai_admin.ensure_access_level(level)
    -> projection child + indexes
    -> identifier child + indexes
    -> reference edge/target children + indexes
    -> each active vector-profile child + indexes
```

Requirements:

- validate level > 0;
- idempotent;
- protected by transaction-scoped advisory lock;
- callable only by a dedicated provisioning role;
- never invoked implicitly from the hot retrieval path;
- no DEFAULT partition.

For known environments, provision ACLs during deployment/startup reconciliation.

For a new ACL discovered during ingestion:

```text
reject current write clearly
-> emit metric/event
-> provision out of band
-> retry idempotent ingestion
```

Do not allow arbitrary application traffic to execute retrieval DDL.

## 19. Index creation and migration locks

Shadow v2 tables avoid modifying live parents in-place.

For large existing partitions, production index creation should support per-child concurrent
index builds where required.

Partitioned-parent indexes cannot simply be created concurrently as one operation on PostgreSQL 17.
Operational migration should be prepared to:

1. create the parent index definition appropriately;
2. build child indexes independently/concurrently when needed;
3. attach them to the parent index;
4. validate before cutover.

For a new empty shadow relation, the cheaper path is:

1. create parent/children;
2. bulk load;
3. build local indexes;
4. validate;
5. start dual-write/catch-up;
6. cut over.

Benchmark whether building HNSW after bulk load is substantially cheaper than maintaining HNSW
through the initial backfill.

## 20. Backfill rules

Backfill joins every retrieval row to the immutable generation ACL:

```sql
JOIN knowledge_document_generation g
  ON g.document_id = source.document_id
 AND g.generation = source.generation
```

and writes:

```text
g.access_level
```

Never derive retired generation ACL from `knowledge_document_lifecycle.access_level`.

For vectors, extract document/generation/chunk identity from legacy metadata only to locate the
generation row. The ACL itself comes from the generation journal.

Rows with no matching generation are migration-reconciliation errors and must not be silently
assigned ACL 1.

## 21. Dual-write / shadow-read cutover

Recommended states:

```text
V1_ONLY
DUAL_WRITE_SHADOW_READ
V2_ONLY
```

### V1_ONLY

Current production behavior.

### DUAL_WRITE_SHADOW_READ

- ingestion writes v1 and v2;
- v1 remains authoritative;
- a configurable sample of reads executes v2 in shadow;
- responses are served from v1;
- v1/v2 results and latency are compared asynchronously inside the request execution budget or
  in a dedicated benchmark environment.

Compare:

- Recall@K against exact/golden truth;
- topK overlap;
- MRR/nDCG;
- unauthorized-result count (must be zero);
- p50/p95/p99;
- planning time;
- buffer reads/hits.

### V2_ONLY

Only after correctness + performance acceptance.

Keep rollback capability until a soak period completes.

## 22. Maintenance paths must become partition-aware

Optimization is incomplete if only SELECT is routed.

Change cleanup APIs conceptually from:

```text
deleteGeneration(documentId, generation)
deleteIds(profile, ids)
```

to:

```text
deleteGeneration(accessLevel, documentId, generation)
deleteIds(profile, accessLevel, ids)
```

The immutable generation ACL is already available to cleanup/reconciliation logic.

This prevents retention/reconciliation from scanning every ACL child.

## 23. `pg_partman` decision

Do not use `pg_partman` for the retrieval corpus in the first implementation.

Why:

- ACL lifecycle is policy-driven, not time-driven;
- ACL values should be explicitly provisioned;
- AKMAI does not want an automatic catch-all/default behavior for security partitions;
- native LIST DDL is simple at the expected ACL cardinality.

Use `pg_partman` where its automation model fits:

- retrieval telemetry;
- query history;
- audit events;
- model invocation logs;
- long-lived reconciliation/event history.

Those tables naturally use `created_at` RANGE partitions and retention/drop policies.

## 24. Benchmark architecture

Create a reproducible PostgreSQL performance harness rather than relying on micro integration
fixtures.

### 24.1 Dataset dimensions

At minimum vary:

- total vectors/chunks: small CI tier + million-row performance tier;
- vector dimensions: current configured profile (1024 in default deployment);
- ACL values: 1 / 2 / 4 / 8 / 16;
- uniform ACL distribution;
- skewed ACL distribution;
- chunks per document: 50 / 300 / 2k / large outlier;
- RU / EN / KK / ZH lexical mix;
- published + retired generations.

### 24.2 Query matrix

Measure:

1. vector global / 1 ACL;
2. vector global / 2 ACL;
3. vector global / 4 ACL;
4. vector global / 8 ACL;
5. vector global / 16 ACL;
6. vector document scoped / 1 document;
7. vector document scoped / several documents;
8. RU FTS;
9. EN FTS;
10. KK trigram;
11. ZH trigram;
12. identifier exact;
13. identifier prefix;
14. identifier partial;
15. reference resolution;
16. canonical chunk revalidation;
17. context adjacency;
18. maintenance delete by generation.

### 24.3 Plan capture

Use:

```sql
EXPLAIN (
    ANALYZE,
    BUFFERS,
    SETTINGS,
    SUMMARY,
    FORMAT JSON
)
```

Store plans as benchmark artifacts.

### 24.4 Metrics

Collect:

- planning time;
- execution time;
- shared hit/read blocks;
- temp I/O;
- rows scanned;
- rows removed by filters;
- indexes used;
- partitions touched;
- HNSW Recall@K;
- p50/p95/p99 under concurrency.

## 25. Merge gates

### 25.1 Correctness

- zero unauthorized retrieval rows;
- generation fencing preserved;
- ACL transition N -> N+1 cannot resurrect N;
- migration backfill preserves historical generation ACL;
- v1/v2 golden quality does not regress beyond an explicitly approved threshold.

### 25.2 Partition routing

For representative large data:

- no partition outside the requested ACL set is scanned;
- execution-time pruning is accepted; `Subplans Removed` is evidence, not the only valid plan shape;
- prepared/generic-plan executions retain routing behavior.

### 25.3 Vector plans

- large global ACL search uses the intended ACL-local HNSW/partial HNSW;
- filtered ANN Recall@K meets the gate;
- document-scoped selective retrieval uses bounded exact candidates rather than a large global
  ANN traversal;
- multi-ACL p99 is tested at 8 and 16 levels.

### 25.4 Lexical / identifier

On representative corpus size:

- RU/EN use intended local/ACL-specific FTS GIN;
- KK/ZH use intended trigram indexes when selective enough;
- exact identifiers use B-tree;
- partial identifiers use trigram GIN.

A sequential scan is not automatically a failure on a tiny partition; cost and rows read are the
real acceptance criteria.

### 25.5 Operations

- provisioning idempotent;
- concurrent provisioning tested;
- missing ACL has an explicit error + metric;
- no DEFAULT partition;
- cleanup routes by ACL;
- index build/cutover process has a rollback path.

## 26. Implementation phases

### Phase 0 — benchmark before DDL

Deliver:

- reproducible seed generator;
- plan JSON capture;
- exact vector ground truth;
- performance report for current v1;
- pgJDBC repeated-execution/generic-plan test.

No storage migration before this baseline exists.

### Phase 1 — typed routing columns

Add typed `document_id`, `generation`, `chunk_id`, `access_level` to the shadow/vector v2
model and make repositories capable of reading them.

This phase isolates the benefit of removing JSON hot-key extraction.

### Phase 2 — physical-layout A/B

Benchmark:

- one typed heap + partial HNSW per ACL;
- LIST ACL partitions + local HNSW.

Make the vector decision from measured results.

For projection/identifier/reference, benchmark LIST against typed single-table indexes with the
same data.

### Phase 3 — exact document vector mode

Implement the selective exact path and benchmark the switching threshold.

### Phase 4 — dual-write + shadow read

Backfill v2, enable dual write, compare sampled reads.

### Phase 5 — cutover

Switch to v2 only after gates pass.

### Phase 6 — operational time partitioning

Only then introduce `pg_partman` for telemetry/audit/history tables where time-range retention
is a natural fit.

## 27. Rejected shortcuts

Do not:

- partition lifecycle or generation control tables;
- partition retrieval corpus by language/domain/time;
- add document-hash subpartitions before evidence requires them;
- retain JSON extraction in hot vector predicates;
- assume `= ANY(array_parameter)` provides the desired ACL pruning;
- use DEFAULT ACL partitions;
- create DDL on demand from ordinary ingestion permissions;
- require indexes in plans on tiny relations where a seq scan is cheaper;
- tune `work_mem`, parallelism, HNSW scan limits globally without concurrency benchmarks;
- claim a fixed 10x/100x performance gain before measurement.

## 28. Strategic conclusion

The likely high-performance end state is:

```text
                immutable generation ACL
                         |
                         v
                  access routing
                         |
       +-----------------+-----------------+
       |                 |                 |
       v                 v                 v
    ACL 1             ACL 2             ACL N
       |                 |                 |
  local ANN/GIN     local ANN/GIN     local ANN/GIN
  local B-tree     local B-tree      local B-tree
       |                 |                 |
       +-----------------+-----------------+
                         |
               lifecycle publication fence
                         |
                         v
                      result
```

The biggest expected wins are not from partitioning in isolation. They come from combining:

- typed hot columns;
- ACL-local candidate structures;
- stable scalar routing predicates;
- exact document-scoped vector search;
- smaller local GIN/B-tree indexes;
- generation-level integrity;
- benchmark-driven HNSW tuning.

The architecture review therefore recommends **benchmark-first shadow storage v2**, with LIST
partitioning as the leading corpus layout and partial HNSW as the mandatory control experiment
for the low-cardinality vector case.

## 29. External technical references

- PostgreSQL 17 table partitioning:
  https://www.postgresql.org/docs/17/ddl-partitioning.html
- PostgreSQL 17 query-planner partition controls:
  https://www.postgresql.org/docs/17/runtime-config-query.html
- PostgreSQL 17 index documentation:
  https://www.postgresql.org/docs/17/indexes.html
- pgvector filtering, iterative scans, partitioning and multitenancy:
  https://github.com/pgvector/pgvector
- pgJDBC server prepared statement behavior:
  https://jdbc.postgresql.org/documentation/server-prepare/
- pg_partman:
  https://github.com/pgpartman/pg_partman
