# AKMAI — Greenfield Retrieval Storage Blueprint

Status: historical greenfield baseline; retrieval lifecycle/topology is
superseded by `retrieval-hot-only-tombstone.md`.  
Branch: `feature/retrieval-partitioning-architecture`  
Database: PostgreSQL 17 + pgvector  
Deployment state: no database has been deployed; schema is free to be redesigned before first release.

## 1. Decision

Design the first production schema directly for ACL-local retrieval.

Primary target:

```text
knowledge_document_generation
        |
        | immutable (document_id, generation, access_level)
        v
+-------------------------- CONTROL PLANE --------------------------+
| knowledge_document_lifecycle                                      |
| knowledge_document_generation                                     |
| knowledge_embedding_profile / runtime / migration                 |
+------------------------------------------------------------------+
        |
        +------------------- RETRIEVAL DATA PLANE ------------------+
        |
        +-- knowledge_search_projection      LIST(access_level)
        +-- document_identifier              LIST(access_level)
        +-- knowledge_reference_target       LIST(access_level)
        +-- knowledge_reference_edge         LIST(access_level)
        +-- knowledge_document_vector_generation LIST(access_level)
        +-- akmai_vector.<profile_table>      LIST(access_level)
```

Every large retrieval/cleanup relation has the same routing key.

No DEFAULT ACL partitions.

The first release should not contain historical ALTER chains or shadow-v2 compatibility code.

## 2. Why the same partition key everywhere

`access_level` has four properties that make it the only suitable common partition key:

1. it is mandatory on every retrieval request after entitlement resolution;
2. it is immutable for one generation;
3. expected cardinality is small;
4. it is a security boundary, so accidentally touching unrelated partitions is undesirable even
   when the final lifecycle predicate would filter the row.

A single partition topology also simplifies:

- planner expectations;
- local index naming;
- cleanup routing;
- re-embedding;
- reconciliation;
- performance tests;
- operational diagnostics.

## 3. Control-plane schema

### 3.1 `knowledge_document_lifecycle`

Keep one row per logical document.

Important hot columns:

```text
document_id              PK
published_generation
access_level
retention_status
lifecycle_status
next_generation
expires_at
claim / lease fields
```

Recommended retrieval-side index:

```sql
CREATE INDEX idx_lifecycle_active_acl_document
ON knowledge_document_lifecycle (access_level, document_id)
INCLUDE (published_generation)
WHERE retention_status = 'ACTIVE';
```

The document PK remains the best candidate-driven lookup when ANN/GIN has already selected a small
number of rows.

Do not partition this table.

### 3.2 `knowledge_document_generation`

Generation is the immutable security/storage identity.

Add/keep:

```text
PRIMARY KEY (document_id, generation)

UNIQUE (document_id, generation, access_level)

access_level BIGINT NOT NULL CHECK (access_level > 0)
chunk_count INTEGER
```

`chunk_count` is written when a generation is published and can later help choose exact
document-scoped vector retrieval without counting a large vector table.

Generation status remains the ingestion/re-embedding journal.

Do not partition this table.

## 4. Retrieval partition registry

Add a storage registry that is explicitly **not an authorization source**:

```sql
CREATE TABLE akmai_retrieval_partition_registry (
    access_level BIGINT PRIMARY KEY CHECK (access_level > 0),
    provisioned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
```

Purpose:

- record physically provisioned ACL values;
- let new embedding profiles create the same child topology;
- validate deployment before accepting traffic.

Authorization remains in API-key/JWT entitlement configuration.

## 5. Projection table

Target:

```sql
CREATE TABLE knowledge_search_projection (
    access_level       BIGINT NOT NULL,
    document_id        VARCHAR(100) NOT NULL,
    generation         BIGINT NOT NULL,
    chunk_id           VARCHAR(100) NOT NULL,
    parent_chunk_id    VARCHAR(100),
    chunk_index        INTEGER NOT NULL,
    text_content       TEXT NOT NULL,
    embedding_text     TEXT NOT NULL,
    language           VARCHAR(32) NOT NULL,
    domain             VARCHAR(50) NOT NULL,
    section_path       TEXT,
    identifiers_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    references_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    projection_version INTEGER NOT NULL DEFAULT 1,

    search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'simple',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,

    search_vector_ru TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'russian',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,

    search_vector_en TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'english',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        chunk_id
    ),

    UNIQUE (
        access_level,
        document_id,
        generation,
        chunk_index
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CHECK (access_level > 0),
    CHECK (generation > 0),
    CHECK (chunk_index >= 0)
)
PARTITION BY LIST (access_level);
```

### 5.1 Projection indexes

Create indexes on the partitioned parent so PostgreSQL creates matching local child indexes.

Canonical / adjacency:

```sql
CREATE INDEX idx_projection_document_generation_chunk_index
ON knowledge_search_projection (
    document_id,
    generation,
    chunk_index
);
```

RU:

```sql
CREATE INDEX idx_projection_fts_ru
ON knowledge_search_projection
USING GIN (search_vector_ru)
WHERE language = 'ru';
```

EN:

```sql
CREATE INDEX idx_projection_fts_en
ON knowledge_search_projection
USING GIN (search_vector_en)
WHERE language = 'en';
```

Generic fallback:

```sql
CREATE INDEX idx_projection_fts_simple
ON knowledge_search_projection
USING GIN (search_vector);
```

Trigram fallback across languages:

```sql
CREATE INDEX idx_projection_text_trgm
ON knowledge_search_projection
USING GIN (lower(text_content) gin_trgm_ops);

CREATE INDEX idx_projection_section_trgm
ON knowledge_search_projection
USING GIN (lower(coalesce(section_path, '')) gin_trgm_ops);
```

These indexes are deliberately broader than language-only trigram partial indexes because RU/EN
also use trigram fallback when FTS does not produce candidates.

## 6. Identifier table

Target:

```sql
CREATE TABLE document_identifier (
    access_level     BIGINT NOT NULL,
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    document_id      VARCHAR(100) NOT NULL,
    generation       BIGINT NOT NULL,
    chunk_id         VARCHAR(100) NOT NULL,
    page_number      INTEGER NOT NULL,
    identifier_type  VARCHAR(50) NOT NULL,
    raw_value        VARCHAR(500) NOT NULL,
    normalized_value VARCHAR(500) NOT NULL,
    context_text     TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (access_level, id),

    UNIQUE (
        access_level,
        document_id,
        generation,
        chunk_id,
        identifier_type,
        normalized_value
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CHECK (access_level > 0),
    CHECK (generation > 0),
    CHECK (page_number >= 0)
)
PARTITION BY LIST (access_level);
```

Recommended parent indexes:

```sql
CREATE INDEX idx_identifier_type_exact
ON document_identifier (
    identifier_type,
    normalized_value,
    created_at DESC
);

CREATE INDEX idx_identifier_exact
ON document_identifier (
    normalized_value,
    created_at DESC
);

CREATE INDEX idx_identifier_type_prefix
ON document_identifier (
    identifier_type,
    normalized_value text_pattern_ops
);

CREATE INDEX idx_identifier_trgm
ON document_identifier
USING GIN (normalized_value gin_trgm_ops);
```

The business UNIQUE definition must also be used by `ON CONFLICT`; the old non-ACL conflict
target must not survive.

## 7. Reference graph

### 7.1 Target

```sql
CREATE TABLE knowledge_reference_target (
    access_level    BIGINT NOT NULL,
    document_id     VARCHAR(100) NOT NULL,
    generation      BIGINT NOT NULL,
    chunk_id        VARCHAR(100) NOT NULL,
    reference_type  VARCHAR(32) NOT NULL,
    canonical_value VARCHAR(200) NOT NULL,
    raw_value       VARCHAR(500) NOT NULL,
    language        VARCHAR(8) NOT NULL,

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        reference_type,
        canonical_value
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    )
)
PARTITION BY LIST (access_level);
```

The canonical reference identity is the key; `chunk_id` is mutable payload for upsert and does not
need to be part of the PK.

### 7.2 Edge

```sql
CREATE TABLE knowledge_reference_edge (
    access_level       BIGINT NOT NULL,
    document_id        VARCHAR(100) NOT NULL,
    generation         BIGINT NOT NULL,
    source_chunk_id    VARCHAR(100) NOT NULL,
    reference_type     VARCHAR(32) NOT NULL,
    canonical_value    VARCHAR(200) NOT NULL,
    raw_value          VARCHAR(500) NOT NULL,
    language           VARCHAR(8) NOT NULL,
    target_scope       VARCHAR(32) NOT NULL,
    target_document_id VARCHAR(100),

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        source_chunk_id,
        target_scope,
        reference_type,
        canonical_value,
        raw_value
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CHECK (target_scope IN ('SAME_DOCUMENT', 'EXPLICIT_DOCUMENT'))
)
PARTITION BY LIST (access_level);
```

The primary key is already ordered for the current same-document source lookup after ACL pruning.

If benchmark shows it useful, add a narrower partial index:

```sql
CREATE INDEX idx_reference_edge_same_document
ON knowledge_reference_edge (
    document_id,
    generation,
    source_chunk_id,
    reference_type,
    canonical_value
)
WHERE target_scope = 'SAME_DOCUMENT';
```

Do not add it before measuring whether the PK is sufficient.

## 8. Vector generation manifest

The manifest can become as large as the vector corpus. Route it by ACL too.

```sql
CREATE TABLE knowledge_document_vector_generation (
    access_level         BIGINT NOT NULL,
    document_id          VARCHAR(100) NOT NULL,
    generation           BIGINT NOT NULL,
    vector_id            UUID NOT NULL,
    chunk_id             VARCHAR(100) NOT NULL,
    embedding_profile_id VARCHAR(128) NOT NULL,
    physical_id_version  SMALLINT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        vector_id
    ),

    UNIQUE (
        access_level,
        document_id,
        generation,
        chunk_id
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    )
)
PARTITION BY LIST (access_level);
```

This makes retention and reconciliation deletes partition-local.

## 9. Vector profile storage

Every embedding profile continues to have its own physical vector table because dimensions and
operator class are profile properties.

Final vector table template:

```sql
CREATE TABLE akmai_vector.<profile_table> (
    access_level BIGINT NOT NULL,
    document_id  VARCHAR(100) NOT NULL,
    generation   BIGINT NOT NULL,
    chunk_id     VARCHAR(100) NOT NULL,
    id           UUID NOT NULL,
    content      TEXT NOT NULL,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding    VECTOR(<dimensions>) NOT NULL,

    PRIMARY KEY (
        access_level,
        id
    ),

    UNIQUE (
        access_level,
        document_id,
        generation,
        chunk_id
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES public.knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CHECK (access_level > 0),
    CHECK (generation > 0)
)
PARTITION BY LIST (access_level);
```

### 9.1 Vector indexes

Create on the partitioned parent:

```sql
CREATE INDEX <profile_table>_embedding_hnsw
ON akmai_vector.<profile_table>
USING HNSW (embedding vector_cosine_ops);
```

PostgreSQL creates physical matching indexes on each existing child, and new partitions inherit
matching indexes.

The UNIQUE index is sufficient for `access_level + document_id + generation` prefix filtering in
the exact path. Benchmark before adding another duplicate B-tree.

### 9.2 JSON contract

The following values are no longer required as hot predicates in JSON:

- document id;
- generation;
- chunk id;
- access level;
- embedding profile id.

They may remain duplicated in metadata for diagnostics only.

No repository may cast JSON text to generation inside a retrieval WHERE/JOIN clause.

## 10. Access partition provisioning

Introduce:

```text
akmai_admin.ensure_access_level(level)
```

Behavior in one transaction:

1. validate positive level;
2. take advisory transaction lock for that ACL;
3. insert into `akmai_retrieval_partition_registry`;
4. create child partitions for:
   - projection;
   - identifier;
   - reference target;
   - reference edge;
   - vector generation manifest;
5. loop all embedding profiles and create the matching vector child.

Because indexes are defined on parents, new children automatically receive matching indexes.

No DEFAULT partition is created.

### 10.1 Privileges

Own DDL with a dedicated role:

```text
akmai_provisioner
```

Ordinary application credentials receive DML only.

Production ingestion must not have permission to create arbitrary partitions.

## 11. Embedding profile provisioning

`EmbeddingProfileStorageManager` becomes a storage provisioner, not just a generic DDL helper.

New profile flow:

```text
validate profile
-> create typed partitioned vector parent
-> create parent HNSW
-> enumerate akmai_retrieval_partition_registry
-> create one vector child per provisioned ACL
-> persist profile
```

Constrain generated vector table names to leave room for child/index suffixes; for example max
32 characters.

HNSW on `vector` remains limited to supported pgvector dimensions; the current default 1024 is
well within the 2000-dimension vector HNSW limit.

## 12. Domain contract: GenerationIdentity

Add:

```java
public record GenerationIdentity(
        String documentId,
        long generation,
        long accessLevel
) {
    public GenerationIdentity {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (accessLevel <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
    }
}
```

All generation-specific persistence APIs receive this identity rather than three independent
arguments.

Examples:

```text
SearchProjectionRepository.saveAll(identity, projections)
DocumentIdentifierRepository.saveAll(identity, identifiers)
ReferenceGraphRepository.saveAll(identity, projections)
VectorGenerationRepository.save(identity, entries)
PostgresGenerationVectorRepository.insertAll(profile, identity, rows)
```

The repository writes `identity.accessLevel()` to physical rows.

## 13. Domain contract: RetrievalHit carries ACL

Extend retrieval hit identity to:

```text
accessLevel
documentId
generation
chunkId
```

This is important for performance.

Initial search receives a set of authorized ACLs, but once a candidate is found its exact ACL is
known. Every downstream operation should use the candidate ACL rather than the original multi-ACL
set.

Result:

```text
initial vector/lexical/identifier
    accessLevels = [1,2,5]
           |
           v
hit = ACL 2 / doc / generation / chunk
           |
           +--> canonical validation: access_level = 2
           +--> reference expansion:  access_level = 2
           +--> adjacency:            access_level = 2
```

This avoids repeated N-partition fan-out inside fusion and expansion.

## 14. RetrievalContext should retain generation-aware document scope

Do not reduce identifier dependencies to `Set<String> documentIds` only.

Introduce conceptually:

```text
RetrievalDocumentScope {
    accessLevel
    documentId
    generation
}
```

This lets vector exact mode use the actual candidate partition and generation.

Lifecycle is still re-checked during retrieval to protect against a concurrent publication switch.

## 15. Global vector query

For one ACL branch:

```sql
WITH nearest AS MATERIALIZED (
    SELECT
        v.access_level,
        v.id,
        v.document_id,
        v.generation,
        v.chunk_id,
        v.content,
        v.metadata,
        v.embedding <=> ? AS distance
    FROM akmai_vector.<profile> v
    WHERE v.access_level = ?
    ORDER BY v.embedding <=> ?
    LIMIT ?
)
SELECT n.*
FROM nearest n
JOIN knowledge_document_lifecycle l
  ON l.document_id = n.document_id
 AND l.published_generation = n.generation
 AND l.access_level = n.access_level
WHERE l.retention_status = 'ACTIVE'
  AND n.distance <= ?
ORDER BY n.distance
LIMIT ?;
```

The outer distance predicate follows pgvector's recommended pattern for indexed nearest-neighbor
queries with a distance threshold.

A benchmark must compare this candidate-first lifecycle validation with the current joined HNSW
shape.

## 16. Multi-ACL vector search

Generate one branch per ACL and merge branch-local topK.

Do not bind the routing key as one array parameter.

Concept:

```text
ACL 1 -> local HNSW topK
ACL 2 -> local HNSW topK
ACL 5 -> local HNSW topK
              |
              v
        global distance sort
              |
              v
             topK
```

Take at least final `topK` from every branch.

Benchmark ACL cardinalities 1, 2, 4, 8, 16 under concurrency.

## 17. Document-scoped exact vector search

This is a separate algorithm, not a filtered HNSW query.

For each ACL group:

```sql
WITH candidates AS MATERIALIZED (
    SELECT
        v.access_level,
        v.id,
        v.document_id,
        v.generation,
        v.chunk_id,
        v.content,
        v.metadata,
        v.embedding
    FROM knowledge_document_lifecycle l
    JOIN akmai_vector.<profile> v
      ON v.access_level = ?
     AND v.document_id = l.document_id
     AND v.generation = l.published_generation
    WHERE l.access_level = ?
      AND l.retention_status = 'ACTIVE'
      AND l.document_id = ANY (?)
)
SELECT
    *,
    embedding <=> ? AS distance
FROM candidates
ORDER BY distance
LIMIT ?;
```

The materialized CTE intentionally prevents ANN from becoming the candidate-access mechanism.

### 17.1 Exact/HNSW switch

Initial selector:

- exact mode when identifier/reference dependency scope is present and small;
- global/local HNSW otherwise.

Preferred later selector:

```text
SUM(published generation chunk_count)
```

for the scoped documents, compared with a configurable exact-candidate threshold.

## 18. Lexical retrieval SQL strategy

For one ACL:

```sql
WHERE p.access_level = ?
  AND p.language = ?
  AND ...
```

Lifecycle publication validation remains in the query.

For multi-ACL requests, build scalar ACL branches and merge branch-local topK by lexical score.

Once a lexical hit is produced, propagate its exact `access_level`.

## 19. Identifier retrieval SQL strategy

Same rule:

```text
one scalar ACL branch
-> local exact/prefix/trigram index
-> lifecycle publication check
-> branch local limit
-> merge
```

Identifier results must include `access_level` and `generation` so subsequent semantic retrieval
does not lose physical routing information.

## 20. Reference and expansion strategy

By the time reference expansion runs, every source hit must contain exact ACL.

Therefore reference reads use:

```sql
WHERE e.access_level = ?
  AND e.document_id = ?
  AND e.generation = ?
  AND e.source_chunk_id = ANY (?)
```

No access-level array is needed downstream.

Same applies to canonical chunk validation and adjacency reads.

## 21. Partition-aware maintenance

Every delete/clone API must include `GenerationIdentity`.

Examples:

```text
deleteGeneration(identity)
cloneGeneration(sourceIdentity, targetIdentity)
deleteVectorIds(profile, identity.accessLevel, ids)
```

Re-embedding normally preserves ACL and therefore clones/writes inside the same ACL child.

A reingestion that changes ACL creates a new generation in a different child; publication switches
the lifecycle pointer atomically.

## 22. Index-budget discipline

Partitioning can make it tempting to create many local indexes.

Do not optimize by index count alone.

Mandatory first-release indexes:

### Projection

- PK / unique chunk index;
- document-generation-chunk-index adjacency;
- RU GIN;
- EN GIN;
- simple GIN;
- text trigram GIN;
- section trigram GIN.

### Identifier

- exact;
- type exact;
- type prefix;
- trigram.

### References

- PKs;
- optional same-document index only after benchmark.

### Vector

- local HNSW inherited from parent;
- unique document/generation/chunk identity.

Every additional index must justify write amplification and cache cost.

## 23. LIST versus partial HNSW control experiment

The concrete target design uses LIST partitions because all retrieval stores benefit from the same
locality and security topology.

However, Phase 0 must benchmark one typed non-partitioned vector table with per-ACL partial HNSW:

```sql
CREATE INDEX ...
USING HNSW (embedding vector_cosine_ops)
WHERE access_level = 1;
```

If the actual production policy has only a few ACLs and partial HNSW produces equal or better
p95/p99 + recall with clearly lower operational cost, the vector relation may remain
non-partitioned while relational search stores use LIST.

This is the only deliberately open physical-layout decision.

## 24. Benchmark dataset

Performance validation must not use the current 1000-row integration corpus as evidence.

Create tiers:

### CI planner tier

Small enough for GitHub Actions, large enough that indexes are cost-relevant.

### Performance tier

At least one million chunks/vectors, with:

- ACL cardinality 1 / 2 / 4 / 8 / 16;
- 80/20 skew and uniform distribution;
- 50 / 300 / 2000 chunks per document;
- current 1024 embedding dimensions;
- published and retired generations;
- multilingual lexical mix.

## 25. Required measurements

Capture:

```sql
EXPLAIN (
    ANALYZE,
    BUFFERS,
    SETTINGS,
    SUMMARY,
    FORMAT JSON
)
```

for:

- global vector;
- multi-ACL vector;
- exact document vector;
- RU FTS;
- EN FTS;
- KK/ZH trigram;
- identifier exact/prefix/partial;
- reference resolution;
- canonical revalidation;
- adjacency;
- generation cleanup.

Also record:

- Recall@5 / Recall@10;
- MRR / nDCG;
- p50 / p95 / p99;
- planning time;
- shared hit/read blocks;
- rows removed by filter;
- partitions touched;
- index sizes;
- total relation sizes.

## 26. Prepared-plan gate

The application uses pgJDBC.

Each performance case must be executed repeatedly, not only once, to exercise server-side prepared
statement and PostgreSQL custom/generic plan behavior.

The selected design must remain stable after statement reuse.

If a critical query only works with `force_custom_plan`, treat that as a design warning, not a
normal production requirement.

## 27. Greenfield Liquibase layout

Replace the unreleased migration chain with a clean bootstrap before implementation merges.

Proposed split:

```text
001-platform.sql
    extensions
    schemas
    control-plane tables

002-retrieval-parents.sql
    projection
    identifier
    references
    vector manifest
    registry

003-retrieval-indexes.sql
    parent B-tree / GIN indexes

004-provisioning.sql
    akmai_admin functions
    initial ACL partition provisioning

005-operational.sql
    idempotency
    reconciliation
    migration journal
    retention-support indexes

006-runtime-seed.sql
    embedding runtime singleton
```

Dynamic vector profile tables remain provisioned from the application/provisioner because vector
dimensions differ by profile.

## 28. `pg_partman`

Do not add it to the core retrieval dependency set.

Native LIST provisioning is deterministic and small-cardinality.

Reserve `pg_partman` for future time-series operational relations:

- retrieval telemetry;
- query history;
- audit events;
- LLM invocation events;
- long-term reconciliation events.

Its retention/premake automation is useful there and not needed for ACL corpus partitions.

## 29. Merge gate for the future implementation

Do not merge storage implementation merely because tests are green.

Required:

### Correctness

- no cross-ACL result;
- generation fence survives reingestion races;
- wrong ACL physical write is rejected by FK;
- no DEFAULT ACL partition;
- missing partition fails clearly.

### Planner

- only requested ACL children are executed on representative partitioned queries;
- large vector search uses the ACL-local ANN graph;
- exact document mode performs bounded candidate retrieval before distance sort;
- expected FTS/identifier indexes are used on representative data;
- repeated prepared executions keep acceptable plans.

### Quality

- Recall@K does not regress beyond an explicit threshold;
- multilingual lexical quality does not regress.

### Performance

- p50/p95/p99 compared against current typed/global baseline;
- multi-ACL p99 measured at 8 and 16 ACLs;
- buffer read amplification decreases or remains justified;
- planning time remains bounded.

### Operations

- provisioning idempotent and concurrency-safe;
- cleanup is ACL-routed;
- adding an embedding profile produces all registered ACL children;
- adding an ACL produces children for all existing profiles.

## 30. Final architecture

```text
                            Request
                               |
                      authorized ACL set
                               |
          +--------------------+--------------------+
          |                    |                    |
        ACL 1                ACL 2                ACL N
          |                    |                    |
  +-------+------+     +-------+------+     +-------+------+
  |              |     |              |     |              |
HNSW          FTS/GIN HNSW          FTS/GIN HNSW          FTS/GIN
B-tree        IDs/ref B-tree        IDs/ref B-tree        IDs/ref
  |              |     |              |     |              |
  +-------+------+     +-------+------+     +-------+------+
          |                    |                    |
          +--------------------+--------------------+
                               |
                       lifecycle fence
                               |
                               v
                         RetrievalHit
                  (ACL, doc, gen, chunk)
                               |
                    downstream single-ACL
                 fusion / refs / adjacency
```

The primary performance principle is not merely “partition by ACL”.

It is:

> Route once at the security boundary, preserve that physical routing identity through the entire
> retrieval pipeline, and ensure every expensive index operation runs on the smallest correct
> corpus.
