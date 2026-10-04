# Adaptive Graph Learning and Replay

## Purpose

This document defines the end-to-end safety and calibration contract for the
adaptive graph in AkmAI.

The graph is not allowed to become a self-validating retrieval subsystem.
Learning, serving, replay calibration, canary telemetry and production approval
remain separate stages with explicit gates.

## End-to-end flow

    validated answer
      -> citation-anchored association learning
      -> bounded evidence accumulation
      -> maintenance scoring
      -> CANDIDATE / WARM / HOT lifecycle
      -> shadow graph expansion
      -> ACTIVE/PUBLISHED revalidation
      -> append-only online graph candidates
      -> competitive admission for HOT candidates
      -> ContextBudget
      -> answer + citation validation
      -> selected/assisted telemetry

    offline:
    versioned replay corpus
      -> BASE_ONLY
      -> GRAPH_APPEND_ONLY
      -> GRAPH_COMPETITIVE(T)
      -> layered utility
      -> threshold sweep
      -> calibration candidate
      -> temporal holdout
      -> quality gate
      -> canary
      -> explicit approval

## Learning contract

### Citation-anchored pairs

Association learning is performed only after citation validation.

A learned pair must contain at least one cited chunk.

    cited <-> cited
        accepted
        context evidence + citation evidence

    cited <-> uncited context
        accepted
        context evidence only

    uncited <-> uncited context
        rejected

This removes the noisiest combinatorial source: two chunks do not become
associated merely because both happened to survive the same context budget.

Graph-origin chunks remain excluded from AssociationLearningRecorder, so a
graph candidate cannot directly reinforce the edge that caused it to be served.

### Query diversity

Each observation carries a privacy-safe query support bucket. Repeated traffic
from the same bucket does not repeatedly increment independent evidence.

The current sketch is bounded to 256 buckets. It is a diversity approximation,
not a precise unique-user or unique-query counter.

### Evidence score

The edge score uses all declared evidence dimensions:

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
    effective_weight = clamp(evidence * freshness, 0, 1)

The scalar score is necessary but not sufficient for promotion.

### Independent evidence gates

Lifecycle promotion has hard evidence gates in addition to the scalar score.

Current bootstrap defaults:

    CANDIDATE -> WARM
        weight >= promote-warm
        distinct_query_support >= 2

    WARM -> HOT
        weight >= promote-hot
        distinct_query_support >= 4
        citation_count >= 1

These defaults are conservative bootstrap controls. They are configurable and
may later be calibrated from replay history, but HOT is intentionally prevented
from being created by context co-occurrence alone.

Demotion remains driven by hysteresis and freshness.

## Retrieval contract

### Shadow expansion

Strong reranked or authoritative seeds query bounded HOT/WARM adjacency.

Per-edge contribution remains:

    seed_strength * edge_weight * band_factor

Multiple contributing edges for the same target are combined with noisy-OR:

    combined = 1 - product(1 - contribution_i)

This keeps multi-seed support bounded in [0, 1] and avoids unbounded additive
scores.

### Online admission

Before a graph candidate can enter the online candidate list, it is revalidated
against the canonical ACTIVE/PUBLISHED projection with explicit ACL and
generation identity.

Failure is fail-open: the pre-graph retrieval list is preserved.

### Competitive admission

The measured serving threshold targets exactly:

    akmai.adaptive-graph.competition.min-graph-score

It is not an edge lifecycle threshold.

Only HOT graph candidates whose aggregated adaptiveGraphScore is at or above
this threshold may compete with base retrieval.

Existing high-authority results remain protected and graph competition is
bounded to at most 1-2 promotions.

ContextBudget still applies the normal chunk/token/document constraints and a
hard graph-context quota.

## Three threshold families

Do not collapse these into one number.

    1. Edge lifecycle
       scoring.promote-warm
       scoring.promote-hot
       scoring.demote-warm
       scoring.demote-hot

    2. Adjacency read filters
       shadow-expansion.min-hot-weight
       shadow-expansion.min-warm-weight

    3. Competitive serving threshold
       competition.min-graph-score = T*

The current replay calibrator targets only family 3.

## Replay modes

A competition threshold cannot be evaluated correctly against only one
graph-disabled baseline, because append-only graph expansion and competitive
reordering are different effects.

Every replay request therefore produces three paired rankings:

    BASE_ONLY
        normal retrieval without graph candidates

    GRAPH_APPEND_ONLY
        graph candidates appended
        competition disabled

    GRAPH_COMPETITIVE(T)
        same graph candidates
        competition enabled at exact threshold T

The request cohort must be identical for every tested threshold.

Duplicate replay keys inside a threshold are rejected. A threshold sweep with a
different request-key set at any threshold is rejected.

## Utility contract

Utility contract version:

    adaptive-graph-ranking-utility-v1

The deterministic ranking utility is a normalized weighted combination of:

    Recall@5
    MRR
    nDCG@10

The versioned corpus stores the weights. The current v1 fixture uses:

    Recall@5  0.50
    MRR       0.20
    nDCG@10   0.30

These are contract data, not permanent runtime constants.

For one request:

    U_base    = utility(BASE_ONLY)
    U_append  = utility(GRAPH_APPEND_ONLY)
    U_comp(T) = utility(GRAPH_COMPETITIVE(T))

    append_only_delta = U_append - U_base
    competition_delta = U_comp(T) - U_append
    overall_delta     = U_comp(T) - U_base

    overall_delta =
        append_only_delta + competition_delta

The observation constructor validates this identity so corrupted or mismatched
replay data cannot enter calibration.

### Safety signal

A competitive result is a safety regression when its Recall@5 is below the
BASE_ONLY Recall@5 for the same request.

A threshold with any replay safety violation cannot pass.

This is intentionally conservative for legal and medical retrieval.

## Threshold calibration

For each tested T:

    minimum independent samples
    mean append-only utility lift
    mean overall utility lift
    mean competition utility lift
    Wilson lower bound on competition benefit probability
    maximum competition regression rate
    p95 latency regression
    zero safety violations

The calibrator selects the lowest tested T that passes every declared gate.

The lowest passing threshold is preferred because it gives the broadest useful
coverage while preserving the measured contract.

The calibration set and holdout set are separate. Prefer a temporal split.

    CALIBRATION
      -> REPLAY_CANDIDATE

    HOLDOUT with unchanged T*
      -> QUALITY_GATE_CANDIDATE

    repository quality gate
      -> CANARY_ELIGIBLE

    bounded canary
      -> APPROVAL_CANDIDATE

    explicit versioned approval
      -> PRODUCTION

No stage silently rewrites production configuration.

## Versioned replay corpus

The initial deterministic fixture is:

    src/test/resources/quality/adaptive-graph-replay-v1.json

It contains:

- an explicit corpus version;
- utility contract version;
- tested thresholds;
- utility weights;
- calibration policy;
- separate CALIBRATION and HOLDOUT cases;
- multilingual cases;
- useful HOT graph candidates;
- harmful/noisy HOT graph candidates.

The v1 corpus is a contract fixture that validates replay mechanics and
statistics. It is not a claim that its selected threshold is already a
production threshold for a real corpus.

A production replay corpus should evolve from captured, privacy-safe,
versioned request fixtures and include:

- real ranking headroom;
- hard negatives;
- multi-relevant and graded relevance cases where appropriate;
- language coverage;
- domain/facet coverage;
- graph-version metadata;
- retrieval/configuration version metadata;
- representative long-tail queries.

## GraphReplayHarness

GraphReplayHarness reuses production components rather than implementing a
parallel ranking model.

The harness executes:

    AdaptiveGraphCompetitiveAdmission
    ContextBudget
    RetrievalBenchmarkEvaluator
    AdaptiveGraphReplayUtility
    AdaptiveGraphThresholdCalibrator

This is important: replay tests the actual competition ordering and context
selection behaviour.

The deterministic harness does not certify production latency. Full
replay/load validation must supply measured latency deltas before canary
approval.

## CI quality gate

Retrieval Quality Gate runs both existing retrieval regression tests and the
adaptive graph replay suite.

It emits:

    target/quality/adaptive-graph-replay-report.json

and uploads it as the workflow artifact:

    adaptive-graph-replay-report

The report records:

- report version;
- corpus version;
- utility contract version;
- exact target configuration parameter;
- selected T*;
- calibration decision;
- holdout decision;
- every threshold evaluation;
- holdout evaluation.

Reviewers can therefore inspect the evidence behind a candidate threshold
instead of reviewing a bare number in configuration.

## Runtime telemetry

AdaptiveGraphUtilityRecorder records whether graph context was selected and
whether graph sources were cited.

This telemetry is useful for canary monitoring and drift detection.

It is not used as a direct reinforcement signal and it is not a replacement for
paired replay, because live traffic does not provide the graph-disabled
counterfactual for the same request.

## Optimization principles

The graph pipeline should optimize in this order:

1. security and lifecycle correctness;
2. false-positive control;
3. independent evidence quality;
4. retrieval quality lift;
5. bounded candidate/context cost;
6. latency and storage efficiency;
7. coverage.

Do not optimize edge count, graph usage rate or graph citation rate as primary
objectives. Those metrics can increase while answer quality gets worse.

## Next calibration evolution

After enough replay history exists, extend calibration independently for:

- lifecycle evidence gates;
- WARM/HOT adjacency minimum weights;
- per-language or per-domain cohorts where data volume supports it;
- graph fan-out and quota vectors;
- half-life and TTL profiles.

Do not introduce per-cohort parameters until sample size is sufficient. Sparse
cohorts should inherit the global approved policy rather than overfit.
