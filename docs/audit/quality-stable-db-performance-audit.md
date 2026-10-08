# Quality Stable Baseline — DB, Pool, SQL and Benchmark Audit

## JDBC round-trip changes

### Semantic ingestion seeding

Before hardening, a new pair required separate JDBC calls for:

- pair existence;
- first endpoint semantic degree;
- second endpoint semantic degree;
- first direction upsert;
- second direction upsert.

After hardening, pair existence and both degree counters are returned by one `SemanticGraphStateRepository.inspect(...)` query, and both semantic directions are written by one multi-row upsert. This reduces the semantic admission/write portion from five JDBC calls to two while preserving the existing transaction, lifecycle locks and node locks.

### DREAM-5 apply

The same three admission reads are replaced by one state probe. Dream keeps its fenced mutation SQL and therefore prioritizes stale-owner correctness over aggressive statement fusion.

## SQL/index audit

`knowledge_chunk_association` is partitioned by `access_level` and then by source identity hash. Existing provisioned indexes begin with:

`source_document_id, source_generation, source_chunk_id`

The consolidated semantic state probe constrains `access_level`, `graph_version`, and one of two exact source identities. It therefore uses the existing partitioning/source-index shape and does not justify a new index without EXPLAIN/production-cardinality evidence.

No speculative index is added in this phase. This avoids extra write amplification, WAL, vacuum work and per-partition index footprint.

## Connection-pool audit

The application intentionally does not hard-code a production Hikari maximum in this hardening pass. Pool sizing depends on PostgreSQL capacity and target deployment concurrency. Previous D28 remediation removed the correctness defect where a document operation could reserve one pooled connection for a session advisory lock and then require a second connection for nested JDBC work.

Current graph node locking uses transaction-scoped PostgreSQL advisory locks on the transaction's existing connection; no second held session-lock connection is introduced.

Pool validation remains operational rather than guessed:

- `hikaricp_connections_pending` is already monitored;
- production capacity sign-off must correlate pending/active connections with p95/p99 and saturation;
- Dream v1 DB concurrency remains one;
- pool-size changes require target-hardware load evidence.

## Transaction/lock order

Graph writers now use the same lock protocol:

1. canonical node ordering;
2. lifecycle row locks;
3. transaction-scoped advisory node locks;
4. graph state read;
5. graph mutation.

This removes three independent advisory-lock implementations and reduces deadlock protocol drift.

## Benchmark gate

The quality-stable candidate must pass, on one exact SHA:

- CI;
- Retrieval Quality Gate;
- Retrieval Storage Final Benchmark;
- Production Image Build.

GitHub Actions timings are useful for regression detection but are not production hardware SLOs. Production p50/p95/p99, throughput and saturation limits remain target-hardware qualification data.
