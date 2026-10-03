# Retrieval archive purge economics

Date: 2026-10-03
Status: Historical baseline — superseded for retrieval topology decisions by
`retrieval-hot-only-tombstone.md`.

> This document records the pre-HOT measurement phase. Its PostgreSQL
> observability principles remain useful, but ACTIVE/ARCHIVED retrieval leaves
> are no longer the implementation target.

## Purpose

The current retrieval topology intentionally keeps cold rows in lightweight
`ARCHIVED` leaves and removes them later through generation reconciliation:

```text
ACL
  -> language
       -> ACTIVE
       -> ARCHIVED
```

This phase does **not** add `RANGE(purge_at)`. It first measures whether
partition-level purge would solve a real production bottleneck.

## Why measurement comes first

The default archive residence is short:

```text
reconciliation.grace-period = 5m
reconciliation.fixed-delay  = 5m
```

A monthly or daily archive bucket would therefore retain rows far beyond the
current purge objective. Making buckets small enough to preserve a five-minute
residence would multiply metadata roughly by:

```text
ACL x language x bucket x vector-profile
```

That is a poor trade until production archive volume proves otherwise.

## Production measurements

The application samples archive economics every 15 minutes by default. The
sampler reads PostgreSQL statistics/catalog data, not archive table contents.

Control-plane gauges:

- pending retired generations;
- pending chunks from generation metadata;
- age of the oldest retired generation awaiting purge.

Per-store gauges with only two tag values, `projection` and `vector`:

- estimated live rows;
- estimated dead rows;
- inserted rows since PostgreSQL statistics reset;
- deleted rows since PostgreSQL statistics reset;
- autovacuum runs since statistics reset;
- physical bytes including indexes and TOAST;
- archive leaf count;
- largest archive leaf size.

Reconciliation publishes run duration, batches and cleaned-generation
throughput.

The row gauges from `pg_stat_user_tables` are estimates. Physical byte gauges
come from `pg_total_relation_size()`.

## Decision signals

Stay with generation-scoped DELETE while all of the following remain true:

1. oldest archive age remains close to the configured grace period plus normal
   scheduler jitter;
2. reconciliation has clear throughput headroom over archive inflow;
3. archive dead-row pressure is removed by autovacuum without sustained growth;
4. archive bytes stay bounded rather than trending upward between purge runs;
5. reconciliation duration is a small fraction of its fixed-delay window.

Investigate partition-level purge when one or more signals are sustained across
real production windows:

- backlog age grows despite available reconciliation runs;
- archive bytes or estimated live rows grow monotonically;
- delete churn creates persistent dead tuples / vacuum pressure;
- reconciliation consumes a material share of its scheduling interval;
- WAL / I/O measurements show row DELETE to be a material database cost.

A topology change is accepted only if a representative benchmark demonstrates
a meaningful operational gain, not merely a faster synthetic DELETE.

## Candidate architectures

### A. Current model — baseline

```text
ACL -> language -> ACTIVE | ARCHIVED
```

Pros:

- already implemented and tested;
- hot indexes disappear immediately after archive movement;
- no extra time partitions;
- generation fencing and reconciliation stay simple.

Cons:

- final purge is row DELETE;
- repeated DELETE can create vacuum work at high churn.

### B. Nested archive range

```text
ACL
  -> language
       -> ARCHIVED
            -> RANGE(purge_at)
```

This provides partition detach/drop, but multiplies child partitions by every
ACL/language/bucket/profile combination. With a short grace period it is likely
to be metadata-heavy and operationally unattractive.

### C. Separate cold archive plane — preferred candidate if scale demands it

```text
HOT retrieval plane
  ACL -> language -> ACTIVE

COLD purge plane
  RANGE(purge_at)
```

ARCHIVED rows are never queried by retrieval, so a future cold plane does not
inherently need the same ACL/language physical partitioning as the hot plane.
This model can preserve logical ACL/language columns while partitioning only on
purge economics.

If production measurements justify partition-level purge, benchmark this model
against nested RANGE before changing production DDL.

## pg_partman position

Do not introduce pg_partman during the measurement phase. Core PostgreSQL
partition management is sufficient for the benchmark. If time partitions win,
pg_partman can then be evaluated for automated creation and retention rather
than being made a prerequisite for the data model.

## Promotion gate for RANGE(purge_at)

Before implementing time buckets in production:

1. collect a representative production observation window;
2. export archive bytes, row churn, dead tuples, autovacuum and reconciliation
   latency/throughput;
3. reproduce the measured load in a benchmark;
4. compare current DELETE, nested RANGE, and separate cold-plane RANGE;
5. include WAL volume, lock behavior, catalog/partition count and vacuum work;
6. require a material measured advantage before accepting the additional DDL
   complexity.

Until that gate is met, generation-scoped reconciliation remains the production
purge mechanism.
