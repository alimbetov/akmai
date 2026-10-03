# Retrieval Storage Phase 0 — Final Physical Architecture

Date: 2026-10-03  
Branch: `feature/retrieval-partitioning-architecture`  
PostgreSQL: 17 + pgvector  
Embedding profile benchmarked: 1024 dimensions

## Final decision

The greenfield retrieval data plane uses three routing dimensions for the two
large corpus stores:

```text
LIST(access_level)
  -> LIST(language)
       -> LIST(storage_state)
```

Applied to:

- `knowledge_search_projection`;
- every dynamic `akmai_vector.<profile>` table.

The smaller relational retrieval stores remain:

```text
LIST(access_level)
```

for:

- `document_identifier`;
- `knowledge_reference_target`;
- `knowledge_reference_edge`;
- `knowledge_document_vector_generation`.

Control-plane tables remain unpartitioned.

## Routing dimensions

### access_level

`access_level` is the mandatory security and retrieval routing key.

It is always a typed physical column and has no database default.

Runtime retrieval uses scalar predicates per ACL branch. Array predicates on
the ACL partition key are prohibited by architecture tests.

### language

Supported routing codes:

```text
kk ru en zh de fr es pt it tr el unknown
```

The first eleven values are business languages. `unknown` is a technical
fallback for mixed or low-confidence chunks.

The language detector belongs before persistence. A chunk is stored in exactly
one language leaf. Future detection may retain the full language probability
distribution in metadata, but physical routing remains single-valued.

Ten access levels and twelve language values yield at most 120 language parents
for projection and for each vector profile.

### storage_state

The storage state is modeled in Java as:

```text
RetrievalStorageState.ACTIVE   = 0
RetrievalStorageState.ARCHIVED = 1
```

It is not a security attribute.

`ACTIVE` is the hot retrieval corpus. `ARCHIVED` is a cold generation that
has been logically removed from retrieval and is waiting for delayed physical
purge.

## Physical leaves

Example projection topology:

```text
knowledge_search_projection
  al_1
    lang_en
      s0 ACTIVE
      s1 ARCHIVED
```

Example vector topology:

```text
akmai_vector.p_<profile>
  al_1
    lang_en
      s0 ACTIVE
      s1 ARCHIVED
```

### ACTIVE projection leaf

Indexes:

- `(document_id, generation, chunk_index)` B-tree;
- generic simple FTS GIN;
- text trigram GIN;
- section-path trigram GIN;
- RU FTS GIN only on RU active leaves;
- EN FTS GIN only on EN active leaves.

### ARCHIVED projection leaf

Only a light `(document_id, generation)` B-tree is provisioned.

No FTS/trigram indexes are maintained.

### ACTIVE vector leaf

Indexes:

- local HNSW using cosine operators;
- `(document_id, generation)` B-tree.

### ARCHIVED vector leaf

Only `(document_id, generation)` B-tree.

No HNSW graph is maintained.

## Semantic retrieval policy

Known query language:

```text
access_level = scalar
AND language = scalar
AND storage_state = ACTIVE
-> one language-local HNSW per ACL branch
```

If the same-language result contains fewer than `topK` candidates after the
similarity threshold, the same query embedding is reused for a cross-language
fallback:

```text
access_level = scalar
AND storage_state = ACTIVE
-> active language leaves in the allowed ACL
```

Results are merged and deduplicated by physical vector id.

This preserves multilingual recall without paying the twelve-language ANN
fan-out on the ordinary same-language path.

When query language is `unknown`, retrieval starts directly in the
cross-language fallback mode.

## Document-scoped semantic retrieval

When candidate documents are already known from identifier/reference retrieval,
the vector path remains exact and bounded:

```text
ACL + ACTIVE + optional language
-> (document_id, generation) candidates
-> exact cosine sort
```

It intentionally avoids HNSW when the candidate chunk count is small.

## Lifecycle

Retention is deliberately two-phase.

### Phase 1 — logical removal / archive

The retention worker retains its existing claim/fencing protocol.

For a claimed generation:

1. vector rows move from `storage_state=ACTIVE` to `ARCHIVED`;
2. projection rows move from `ACTIVE` to `ARCHIVED`;
3. small identifier/reference rows are deleted immediately;
4. lifecycle becomes logically deleted and `published_generation` is cleared;
5. the generation becomes `RETIRED` with `cleanup_required=true`.

Because `storage_state` is a partition key, PostgreSQL physically routes the
updated rows from the hot leaf to the cold leaf.

The expensive HNSW/GIN indexes therefore shrink as soon as retention archives
the generation.

### Phase 2 — delayed physical purge

The existing generation reconciliation service is the purge mechanism.

After `reconciliation.grace-period`:

- `RETIRED` generations are deleted from `ARCHIVED`;
- failed staging generations are deleted from `ACTIVE`;
- projection/vector manifest rows are removed;
- generation status becomes `CLEANED`.

This avoids maintaining a second purge scheduler and keeps cleanup inside the
existing transaction/retry/observability model.

## Why there is no archive time partition yet

An `ARCHIVED` leaf can contain generations with different future deletion
times, so truncating the whole leaf would be unsafe.

A fourth `RANGE(purge_at)` level was deliberately not introduced in Phase 0:

- archive residence is currently short and controlled by reconciliation grace;
- an additional time dimension would multiply metadata and DDL complexity;
- generation-scoped DELETE in a cold leaf has no HNSW/GIN maintenance cost;
- no production data exists, so a later archive-range layer can still be added
  if measured retention volume justifies it.

`pg_partman` is therefore not used for the retrieval corpus. It remains a
good fit for independent operational/time-series tables.

## Integrity invariants

1. `access_level`, `language`, `storage_state`, `document_id`,
   `generation` and `chunk_id` are typed routing columns where applicable.
2. `UNIQUE(document_id, generation, access_level)` protects generation ACL
   identity.
3. Retrieval rows reference generation ACL identity by foreign key where
   practical.
4. No DEFAULT ACL/language/state partition is created.
5. Supported ACL/language topology is provisioned explicitly and idempotently.
6. Published retrieval always requires `storage_state = ACTIVE`.
7. HNSW and large lexical indexes exist only on ACTIVE leaves.
8. Unknown/unprovisioned routing values fail closed at INSERT.
9. Downstream retrieval preserves exact ACL/generation identity.
10. Cross-language retrieval never scans ARCHIVED leaves.

## Remaining merge gates

Before this branch can leave draft state:

1. exact-head clean Maven verify;
2. 1024d ACL/concurrency decision matrix on the final SHA;
3. 1024d language/storage benchmark on the final SHA;
4. confirm same-language plan touches only one ACTIVE language leaf;
5. confirm cross-language plan touches ACTIVE leaves only;
6. confirm no HNSW exists on ARCHIVED vector leaves;
7. update PR title/body to describe the production architecture.
