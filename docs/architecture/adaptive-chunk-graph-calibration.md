# Adaptive Chunk Graph calibration and capacity model

## Purpose

Adaptive Chunk Graph parameters are capacity and quality controls derived from measured AkmAI behaviour. They are not permanent design constants.

The subsystem uses a gated loop:

    measure -> calibrate -> replay/load validate -> approve -> canary

Runtime traffic must never rewrite its own degree, activation, decay, partition or maintenance limits automatically. That would create a self-validating feedback loop.

## Separate authoritative and adaptive measurements

AkmAI has two different edge populations.

Authoritative reference edges live in knowledge_reference_edge. They are explicit references extracted from documents. They do not have CANDIDATE, ACTIVE or DECAYED state and must not be counted as learned edges.

Future adaptive edges live in knowledge_chunk_association. This table owns learned weight, lifecycle state, citation/context support and query-diversity evidence.

Reference degree is therefore only a cold-start structural prior. Once shadow learning has enough history, adaptive parameters are calibrated from adaptive-graph telemetry and retrieval quality.

## Calibration lifecycle

    BOOTSTRAP
      -> SHADOW
      -> CALIBRATED
      -> APPROVED
      -> CANARY / PRODUCTION

Every calibration report records the measured interval, graph version, ontology version when present, retrieval configuration, measurements, candidate parameters, quality results and capacity results.

## Real HOT chunk count

Generations are per document, so a global MAX generation is incorrect.

Use the publication fence already present in AkmAI:

    SELECT COUNT(*)
    FROM knowledge_search_projection p
    JOIN knowledge_document_lifecycle l
      ON l.document_id = p.document_id
     AND l.published_generation = p.generation
     AND l.access_level = p.access_level
    WHERE l.retention_status = 'ACTIVE';

Reports should also group by access level and language because those are real physical routing dimensions.

## Cold-start structural degree

Before adaptive data exists, measure explicit reference degree only on ACTIVE/PUBLISHED generations.

    SELECT
        percentile_disc(0.50) WITHIN GROUP (ORDER BY ref_count) AS p50,
        percentile_disc(0.90) WITHIN GROUP (ORDER BY ref_count) AS p90,
        percentile_disc(0.95) WITHIN GROUP (ORDER BY ref_count) AS p95,
        percentile_disc(0.99) WITHIN GROUP (ORDER BY ref_count) AS p99,
        max(ref_count) AS max_refs
    FROM (
        SELECT
            e.access_level,
            e.document_id,
            e.generation,
            e.source_chunk_id,
            count(*) AS ref_count
        FROM knowledge_reference_edge e
        JOIN knowledge_document_lifecycle l
          ON l.document_id = e.document_id
         AND l.published_generation = e.generation
         AND l.access_level = e.access_level
        WHERE l.retention_status = 'ACTIVE'
        GROUP BY
            e.access_level,
            e.document_id,
            e.generation,
            e.source_chunk_id
    ) degree;

This describes natural structural connectivity. It does not directly determine learned graph fan-out.

## Degree calibration

A bootstrap candidate may use:

    K_structural = ceil(p95_reference_degree * safety_factor)

The hard retrieval budget also bounds graph fan-out:

    K_budget <= floor(remaining_candidate_budget / graph_seed_count)

The production max-active-neighbors is selected by a parameter sweep, for example 4, 8, 12, 16, 24 and 32, evaluated against:

- graph expansion precision;
- Recall / nDCG / MRR where appropriate;
- citation lift;
- answer-quality evaluation;
- p95 and p99 retrieval latency;
- candidate count and graph lookup I/O.

Choose the smallest K on the useful quality/latency frontier. Do not promote K only because a formula produced it.

## Shadow graph measurements

After shadow learning exists, measure separately:

- candidate degree;
- active degree;
- accepted expansion degree;
- cited expansion degree;

with p50/p90/p95/p99 distributions by ACL.

The most useful distribution is not stored edge degree. It is how many graph neighbours per strong seed survive semantic compatibility and downstream reranking.

## Activation threshold

Global QPS is not a valid activation threshold for one edge. High traffic does not imply that a particular relation is trustworthy.

Activation should depend on independent evidence dimensions:

- context_count;
- citation_count;
- distinct_query_support;
- last_reinforced_at;
- origin mix.

Calibration procedure:

1. collect CANDIDATE edges in shadow mode;
2. replay later queries without allowing graph expansion to affect retrieval;
3. label whether each candidate neighbour would have been useful;
4. evaluate precision/recall for activation thresholds;
5. choose the lowest threshold that meets the required precision and graph growth bounds.

A temporary bootstrap threshold is allowed, but it is explicitly provisional.

## Per-band degree calibration

Adaptive degree is calibrated as a vector of quotas rather than one scalar K.

```text
K_total =
    K_hot
  + K_warm
  + K_candidate
```

The total still respects the retrieval/storage budget, but each band serves a
different purpose:

- HOT: high-confidence exploitation;
- WARM: useful but less-established associations;
- CANDIDATE: bounded exploration and evidence collection.

A calibration sweep should vary both the total and its allocation, for example:

```text
(4, 4, 8)
(8, 4, 8)
(8, 8, 8)
(12, 8, 12)
```

These tuples are test points only, not defaults.

Evaluate:

- citation lift from HOT;
- incremental recall from WARM;
- promotion precision from CANDIDATE;
- eviction rate;
- band churn;
- graph bytes per active chunk;
- p95/p99 lookup and maintenance latency.

The selected configuration should leave enough CANDIDATE capacity for new edges
to prove themselves while preventing low-confidence storage from dominating.

### Hysteresis calibration

Promotion and demotion use different thresholds:

```text
promote_to_hot > demote_from_hot
promote_to_warm > demote_from_warm
```

Measure oscillation rate around thresholds. Excessive HOT/WARM transitions mean
the hysteresis gap is too small or decay is too aggressive.

### Quota enforcement

Maintenance enforces quota independently for each source and band.

For each overfull band:

1. rank edges by effective score and evidence;
2. retain the configured quota;
3. demote or purge overflow according to lifecycle rules;
4. emit eviction/churn metrics.

Quota enforcement must be idempotent and deterministic.

## Edge weight

Do not use an unbounded counter.

Conceptually:

    support   = saturating(independent positive evidence)
    quality   = bounded(citation/context evidence)
    diversity = bounded(distinct semantic query fingerprints)
    freshness = exp(-lambda * age)

    weight = clamp(support * quality * diversity * freshness, 0, 1)

The functional form is architecture. Coefficients are calibration data.

## Decay and half-life

Corpus change frequency is a useful prior but is not sufficient by itself.

Once shadow history exists, prefer measurements such as:

- time to second reinforcement;
- interval between reinforcements;
- time to last use before decay.

Different volatility profiles can be approved for stable law/standards, moderate technical documentation and fast news/operational content.

Relationships such as active TTL near two half-lives and purge TTL near three half-lives are bootstrap priors only and must be validated.

## Candidate TTL

Candidate TTL should follow the observed time needed to obtain a second or third independent positive signal.

Measure p50/p90/p95 time-to-second-support. A TTL shorter than the natural re-observation interval drops useful edges; a much longer TTL increases storage without quality benefit.

## Compaction capacity

Compaction is a queueing problem.

Measure:

- transition rate to DECAYED;
- candidate expiration rate;
- rows processed per second per worker;
- lock wait;
- WAL;
- dead tuples and autovacuum behaviour.

Required service capacity is:

    maintenance_capacity >= p95_arrival_rate * safety_factor

For scheduled workers:

    batch_size * runs_per_period * effective_workers
      >= p95_rows_requiring_maintenance_per_period * safety_factor

Implementation should use bounded batches and FOR UPDATE SKIP LOCKED, consistent with AkmAI retention/reconciliation patterns.

## Partition-count calibration

Storage remains:

    LIST(access_level)
      -> fixed HASH(source identity)

Hash bucket count is also measured. Evaluate values such as 8, 16, 32 and 64 against:

- rows and bytes per leaf;
- adjacency lookup p95/p99;
- concurrent learning upserts;
- maintenance throughput;
- planner overhead;
- autovacuum and retention cleanup.

Bucket count is fixed for a provisioned ACL partition until an explicit migration. It never auto-changes at runtime.

## QPS measurement

Do not depend on matching fragile SQL text in pg_stat_statements.

AkmAI should consume explicit Micrometer counters/timers for retrieval requests, strategy calls, graph lookups, graph candidates and accepted graph expansions. Database statistics remain useful for I/O, WAL and storage analysis.

## Calibration report

The calculator generates an advisory report. It never mutates production configuration.

Conceptual fields:

    reportVersion
    measuredFrom
    measuredTo
    graphMeasurements
    candidateParameters
    qualityEvaluation
    capacityEvaluation
    decision

Decision states:

    INSUFFICIENT_DATA
    REJECTED
    CANARY_ELIGIBLE

Only CANARY_ELIGIBLE can be promoted.

## Candidate configuration

Configuration belongs under the existing AkmAI namespace:

    akmai:
      adaptive-graph:
        learning-enabled: false
        maintenance-enabled: false
        shadow-expansion-enabled: false
        expansion-enabled: false
        max-active-neighbors: ...
        max-candidates: ...
        activation:
          minimum-weight: ...
          minimum-distinct-query-support: ...
        decay:
          half-life: ...
        candidate-ttl: ...
        decayed-ttl: ...
        compaction:
          fixed-delay: ...
          batch-size: ...
          parallelism: ...
        storage:
          hash-buckets-per-acl: ...

## Quality gates

Do not hard-code universal quality claims such as a specific Recall lift or tolerated p99 regression.

Record the current graph-disabled baseline and explicit project SLOs first.

Every candidate parameter set is evaluated against:

1. graph-disabled baseline;
2. shadow/oracle graph results;
3. graph-enabled replay;
4. ACL and generation correctness;
5. storage/load behaviour.

Security regressions are always blocking.

## Bootstrap without learned edges

If the adaptive graph has no data:

1. measure current HOT chunk count;
2. measure explicit reference degree as structural prior;
3. provision a conservative bounded degree;
4. keep online graph expansion disabled;
5. enable shadow learning;
6. collect independent evidence;
7. run calibration;
8. enable shadow expansion only after quality/capacity gates.

Initial numbers are hypotheses designed to be replaced by evidence.
