# HOT-only retrieval plane and destructive lifecycle

Date: 2026-10-03
Status: implementation under final review
Prototype reference: `ab24b7e991280278622ce2fa887fe32f12573813`

## Decision

The retrieval plane stores searchable HOT payload only:

```text
LIST(access_level)
  -> LIST(language)
       -> terminal HOT leaf
```

There is no retrieval `storage_state` and no `_s0/_s1` layer.

## Mandatory invariants

1. Retrieval visibility is fenced by the lifecycle row: the payload
   `(document_id, generation, access_level)` must match
   `published_generation`, the requested ACL scope, and
   `retention_status = ACTIVE`.
2. Every generation-owned retrieval row keeps the database FK to
   `knowledge_document_generation(document_id, generation, access_level)`.
3. Every HOT vector language leaf owns its local HNSW and
   `(document_id, generation)` B-tree. Projection language leaves own their
   local lexical indexes.
4. Retention lock order is lifecycle first, generation second.
5. Replacement publication moves the previous generation from `PUBLISHED`
   to `RETIRING`; `RETIRING` is not a completed retirement state.
6. A standalone retirement tombstone is committed as `PURGING` before the
   first destructive payload mutation. It is not FK-bound to generation or
   embedding-profile control rows.
7. The generation can become `RETIRED` only after every generation-owned
   payload store is verified empty and the tombstone becomes `PURGED`.
8. Global ANN keeps HNSW candidate-first ordering. Each ACL/language branch
   takes a bounded nearest-neighbor candidate set, then applies the lifecycle
   publication fence. Branch health compares raw and published candidate
   counts; a stale-crowded branch automatically retries with a larger bounded
   candidate set. If the retry ceiling is reached, a lifecycle-fenced fallback
   prioritizes correctness over latency.
9. Normal reconciliation is verification-first. Repair is a separate code
   path and does not modify historical tombstone facts.
10. Tombstones and audit records never contain document text or embeddings.

## Recovery model

Derived retrieval payload has no per-generation restore guarantee. PITR is a
full PostgreSQL recovery mechanism, not a retrieval restore API. Regeneration
comes only from the canonical source through the ingestion pipeline.

## Idempotency

The logical cleanup key is
`(document_id, generation, access_level)`. Generation-atomic purge retries
restart after transaction rollback. Destructive DELETE operations remain
idempotent and final zero-residual verification is authoritative.

## Concurrency

Retention candidate claiming may use `FOR UPDATE SKIP LOCKED`. Once a
generation is claimed, the authoritative lifecycle row is locked with
`FOR UPDATE`; the generation row is locked second. Isolation remains
`READ COMMITTED`.

Production acceptance requires an integration test proving that retention and
ingestion/re-ingestion publication cannot deadlock, delete a newly published
generation, or cross ACL boundaries.

## Acceptance gates

This implementation phase requires:

- correctness and ACL isolation preserved;
- published-generation visibility cannot be bypassed by candidate limits;
- the normal ANN path remains HNSW-backed and avoids full leaf scans;
- retrieval recall and latency benchmarks show no material regression;
- purge/tombstone state transitions and rollback safety are covered;
- retention/publication lock ordering is deadlock-safe;
- physical/catalog topology is simpler.

The A-vs-D economics campaign is intentionally deferred to a separately
approved measurement phase. That follow-up must quantify retention throughput,
WAL, dead tuples/vacuum pressure and storage footprint before those economic
advantages are claimed as measured production results.
