# Adaptive Chunk Graph calibration and capacity model

## Purpose

Adaptive Chunk Graph parameters are capacity and quality controls derived from measured AkmAI behaviour. They are not permanent design constants.

The subsystem uses a gated loop:

    shadow measure
      -> calibration candidate
      -> independent replay
      -> quality gate
      -> canary
      -> explicit approval
      -> production

Runtime traffic must never rewrite its own degree, activation, decay, partition or maintenance limits automatically. That would create a self-validating feedback loop. Calibration is advisory and produces versioned candidate parameters only.

## Separate authoritative and adaptive measurements

AkmAI has two different edge populations.

Authoritative reference edges live in knowledge_reference_edge. They are explicit references extracted from documents. They do not have CANDIDATE, ACTIVE or DECAYED state and must not be counted as learned edges.

Future adaptive edges live in knowledge_chunk_association. This table owns learned weight, lifecycle state, citation/context support and query-diversity evidence.

Reference degree is therefore only a cold-start structural prior. Once shadow learning has enough history, adaptive parameters are calibrated from adaptive-graph telemetry and retrieval quality.

## Calibration lifecycle

    BOOTSTRAP
      -> SHADOW
      -> REPLAY_CANDIDATE
      -> REPLAY_VALIDATED
      -> QUALITY_GATE_CANDIDATE
      -> CANARY_ELIGIBLE
      -> CANARY
      -> APPROVAL_CANDIDATE
      -> APPROVED
      -> PRODUCTION

Calibration and replay must use different observations. Prefer a temporal split so the threshold is selected on an older interval and validated on a later holdout interval. A threshold that only works on the calibration set is rejected.

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

## Threshold taxonomy

AkmAI has three different threshold families. They serve different decisions and must not be calibrated as if they were interchangeable.

### Edge lifecycle thresholds

`scoring.promote-warm`, `scoring.promote-hot`, `scoring.demote-warm` and `scoring.demote-hot` govern learned edge lifecycle transitions. They operate on the edge evidence weight produced from distinct-query, context, citation and freshness evidence.

Global QPS is not a valid lifecycle threshold for one edge. High traffic does not imply that a particular relation is trustworthy.

### Adjacency read thresholds

`shadow-expansion.min-hot-weight` and `shadow-expansion.min-warm-weight` decide which stored HOT/WARM edges are eligible to contribute to graph candidate generation. They operate on edge weight, not on the final aggregated candidate score.

### Competitive serving threshold T*

The measured threshold in this phase targets exactly:

    akmai.adaptive-graph.competition.min-graph-score

`AdaptiveGraphCompetitiveAdmission` applies this threshold only to graph candidates whose strongest band is HOT. The value compared with T* is `adaptiveGraphScore`, the aggregated candidate score produced by the bounded graph expansion planner. T* therefore controls whether an already-generated HOT graph candidate may compete for at most the configured 1-2 promoted positions; it does not promote graph edges to HOT.

Calibration procedure for T*:

1. collect graph candidate/replay inputs without changing the graph evidence lifecycle;
2. record the exact aggregated graph candidate score used by competitive admission;
3. split requests into a calibration interval and a later holdout replay interval;
4. replay every candidate T* against the same calibration request cohort;
5. for every T*, compare graph-enabled replay with the graph-disabled baseline on the same requests;
6. require the identical replay-key cohort at every threshold and reject duplicates;
7. choose the lowest T* that satisfies the pre-declared utility, confidence, regression, latency and safety gates;
8. replay that T* unchanged on the holdout request cohort;
9. only a holdout result that passes the same statistical gates becomes QUALITY_GATE_CANDIDATE;
10. the repository quality gate must pass before that candidate becomes CANARY_ELIGIBLE.

The current application default `competition.min-graph-score=0.70` is a bootstrap value only. Calibration produces a candidate replacement for that setting; it never rewrites the setting at runtime. User feedback is not part of this contract.

## Measured graph utility without user feedback

The primary calibration signal is system-observed replay utility, not
thumbs-up/down or other explicit user feedback.

Competition calibration uses three paired modes for the same request:

    BASE_ONLY
    GRAPH_APPEND_ONLY
    GRAPH_COMPETITIVE(T)

This separation is required because append-only graph expansion and competitive
reordering are different interventions. The serving threshold T* must not claim
utility that was already delivered by append-only graph expansion.

The replay harness executes every candidate threshold against the same request
cohort. Calibration rejects a sweep when request identities differ across
thresholds and rejects duplicate replay keys within one threshold.

Each request-level replay observation contains:

    replay_key
    candidate_threshold
    overall_utility_delta
    append_only_utility_delta
    competition_utility_delta
    latency_delta_ms
    safety_violation

The utility identity is mandatory:

    overall_utility_delta
      = append_only_utility_delta
      + competition_utility_delta

The constructor validates this equality within a small numerical tolerance.

The current deterministic utility contract is versioned as:

    adaptive-graph-ranking-utility-v1

and combines:

    Recall@5
    MRR
    nDCG@10

The versioned corpus stores the weights. Weights are calibration data, not
runtime constants.

A competition result is considered a measurable benefit when:

    competition_utility_delta
      >= minimum_meaningful_competition_delta

For each threshold T the calibrator records:

- sample count;
- mean append-only utility lift;
- mean overall utility lift;
- mean competition utility lift;
- competition benefit rate;
- competition regression rate;
- Wilson lower confidence bound on benefit probability;
- p95 latency delta;
- safety violations.

A threshold passes only when all configured gates hold:

    samples >= minimum_samples
    mean_append_only_utility_delta
      >= minimum_mean_append_only_utility_lift
    mean_overall_utility_delta
      >= minimum_mean_overall_utility_lift
    mean_competition_utility_delta
      >= minimum_mean_competition_utility_lift
    conservative_benefit_probability
      >= minimum_benefit_probability
    regression_rate <= maximum_regression_rate
    p95_latency_delta <= maximum_latency_regression
    safety_violations == 0

The safety gate currently treats a drop in competitive Recall@5 below the
BASE_ONLY Recall@5 for the same request as blocking.

Among passing thresholds, select the lowest one. This maximizes useful coverage
while preserving the declared safety/quality contract.

The Java reference implementation is AdaptiveGraphThresholdCalibrator. It has
two separate operations:

    calibrate(calibration_set)
        -> REPLAY_CANDIDATE

    validateReplay(holdout_set, candidate_threshold)
        -> QUALITY_GATE_CANDIDATE | REJECTED | INSUFFICIENT_DATA

Neither operation changes runtime configuration. The calibrator's target is
akmai.adaptive-graph.competition.min-graph-score; edge lifecycle thresholds and
adjacency minimum weights are calibrated separately.

The deterministic GraphReplayHarness reuses production
AdaptiveGraphCompetitiveAdmission and ContextBudget. Its versioned v1 corpus
lives at:

    src/test/resources/quality/adaptive-graph-replay-v1.json

The Retrieval Quality Gate emits and uploads:

    target/quality/adaptive-graph-replay-report.json

This fixture validates replay mechanics and statistical gating. Full
replay/load validation must still supply measured latency/capacity evidence
before canary approval.

### Canary and approval

The existing multilingual retrieval regression suite remains a blocking safety gate, but it is not by itself a calibration corpus: its healthy baseline is intentionally at the quality ceiling for its small deterministic cases, so it has no useful headroom for measuring positive graph lift.

The graph replay corpus must therefore contain harder request-level cases with real ranking headroom, graded or multi-relevant targets where appropriate, hard negatives, and representative language/domain coverage. Existing Recall@5, MRR and nDCG@10 evaluation machinery should be reused as deterministic metrics rather than introducing user feedback or an unconstrained LLM judge.

`AdaptiveGraphUtilityRecorder` provides runtime selected/assisted observability for controlled online traffic. Those counters are canary evidence and operational telemetry; they are not a substitute for paired replay utility because live traffic has no graph-disabled counterfactual for the same request.

CANARY_ELIGIBLE is not production approval. Canary runs the already-selected threshold on bounded traffic and measures the same quality, safety, capacity and latency invariants. The threshold must not be retuned from canary traffic in place.

A successful canary produces an APPROVAL_CANDIDATE. Final approval is an explicit versioned configuration decision. A failed canary returns to shadow/replay calibration; it never silently lowers the gate.

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

The implemented evidence score uses the three configured evidence dimensions explicitly:

    distinct = saturating(distinct_query_support, distinct_query_scale)
    context  = saturating(context_count, context_scale)
    citation = saturating(citation_count, citation_scale)

    evidence =
        (
            distinct_query_weight * distinct
          + context_weight        * context
          + citation_weight       * citation
        )
        / total_evidence_weight

    freshness = 0.5 ^ age_in_half_lives
    weight = clamp(evidence * freshness, 0, 1)

Context co-occurrence remains a weaker signal by configuration, but it must not be declared in configuration and then omitted from the actual score. The scalar score is necessary but not sufficient for lifecycle promotion.

Current bootstrap evidence gates are:

    CANDIDATE -> WARM
      distinct_query_support >= 2

    WARM -> HOT
      distinct_query_support >= 4
      citation_count >= 1

AssociationLearningRecorder also requires every learned pair to contain at least one cited chunk. Uncited-to-uncited context pairs are not learned.

The functional form and safety invariants are architecture; coefficients and support cutoffs remain calibration data.

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

Calibration decision states:

    INSUFFICIENT_DATA
    REJECTED
    REPLAY_CANDIDATE

Independent replay decision states:

    INSUFFICIENT_DATA
    REJECTED
    QUALITY_GATE_CANDIDATE

The repository quality gate promotes QUALITY_GATE_CANDIDATE to CANARY_ELIGIBLE. Only CANARY_ELIGIBLE can enter a bounded canary. Canary success produces an APPROVAL_CANDIDATE and still requires explicit approval before production.

## Candidate configuration

Calibration reports must name the exact runtime parameter they target. The competitive serving threshold belongs to the existing AkmAI configuration:

    akmai:
      adaptive-graph:
        learning-enabled: false
        maintenance-enabled: false
        shadow-expansion-enabled: false
        expansion-enabled: false

        competition:
          enabled: false
          max-promotions: 1..2
          protected-base-prefix: ...
          min-graph-score: T*

        shadow-expansion:
          min-hot-weight: ...
          min-warm-weight: ...
          max-seeds: ...
          max-candidates: ...

        scoring:
          promote-warm: ...
          demote-warm: ...
          promote-hot: ...
          demote-hot: ...
          minimum-distinct-query-support-warm: ...
          minimum-distinct-query-support-hot: ...
          minimum-citation-count-hot: ...
          half-life: ...

        quotas:
          hot: ...
          warm: ...
          candidate: ...

        storage:
          hash-buckets-per-acl: 32

The calibration report may recommend T*, but applying it remains a versioned deployment/configuration action. It does not enable `competition.enabled` or `expansion-enabled`.

## Quality gates

Do not hard-code universal quality claims such as a specific Recall lift or tolerated p99 regression.

Record the current baseline and explicit project SLOs first.

Competition candidates are evaluated against paired modes:

1. BASE_ONLY;
2. GRAPH_APPEND_ONLY;
3. GRAPH_COMPETITIVE(T);
4. shadow/oracle graph results where available;
5. ACL and generation correctness;
6. storage/load behaviour.

This prevents competition T* from inheriting utility that belongs to append-only graph expansion.

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
