# Measured Adaptive Retrieval v1

## Goal

Move retrieval optimization from fixed heuristics to measurable, reversible decisions without changing production ranking before evidence exists.

The v1 rollout has three safety rules:

1. Retrieval attribution is observational and fail-soft.
2. Adaptive planning is shadow-only and disabled by default.
3. Weighted RRF is available but disabled by default.

## Attribution funnel

Every request can be measured through the same bounded stages:

`PRODUCED -> FUSED -> RERANKED -> SELECTED -> CITED -> GROUNDED`

Metrics use bounded `stage` and `strategy` tags. They never include query text, document ids, chunk ids, access levels, or other high-cardinality request data.

Primary metric:

`akmai.retrieval.attribution{stage,strategy}`

This supports lane-level utility calculations such as:

- selected / produced
- cited / selected
- grounded / produced
- grounded / strategy latency

## Shadow adaptive planner

Configuration prefix:

`akmai.retrieval.adaptive-planner`

Properties:

- `shadow-enabled` default `false`
- `concept-confidence-threshold` default `0.65`
- `exact-concept-confidence-threshold` default `0.95`

The shadow planner does not change the executed `RetrievalPlan`. It compares the current lane set with a conservative recommendation:

- generic semantic query: `VECTOR + LEXICAL + REFERENCE`
- strong non-exact concept query: `VECTOR + LEXICAL + CONCEPT + REFERENCE`
- exact concept query: `VECTOR + CONCEPT + REFERENCE`
- identifier-scoped queries retain `IDENTIFIER` and apply the same semantic policy inside the identifier scope

Metrics:

- `akmai.retrieval.planner.lanes{mode,strategy}`
- `akmai.retrieval.planner.delta{action,strategy}`
- `akmai.retrieval.planner.queries{class}`

A production execution mode must not be added until shadow data demonstrates no meaningful grounded-recall regression.

## Concept retrieval budget

Configuration prefix:

`akmai.retrieval.concept`

Properties:

- `enabled` default `true`
- `max-query-concepts` default `4`
- `top-k` default `4`
- `min-confidence` default `0.65`

This removes the previous coupling between concept retrieval and `lexical-limit` while preserving the existing default result budget.

## Controlled weighted RRF

Configuration prefix:

`akmai.retrieval.fusion`

Properties:

- `weighted-enabled` default `false`
- `identifier-weight` default `1.0`
- `vector-weight` default `1.0`
- `lexical-weight` default `1.0`
- `concept-weight` default `1.0`
- `reference-weight` default `1.0`
- `graph-weight` default `1.0`

When disabled, fusion remains exactly standard reciprocal-rank fusion:

`score += 1 / (rrfK + rank)`

When enabled:

`score += strategyWeight / (rrfK + rank)`

Authority tiers remain a separate first-order ordering constraint and are not replaced by fusion weights.

## Rollout gates

Recommended sequence:

1. Deploy attribution with planner shadow disabled and weighted RRF disabled.
2. Establish a baseline for grounded utility per lane and p50/p95 lane latency.
3. Enable planner shadow only.
4. Compare current vs shadow lane counts and estimate avoidable work.
5. Evaluate candidate RRF weights offline against the retrieval golden set.
6. Enable weighted RRF only behind a controlled canary after quality gates pass.
7. Add adaptive planner execution only as a separate follow-up change with an immediate kill switch.

## Non-goals

This version deliberately does not:

- hard-filter retrieval by semantic domain or concept;
- disable vector recall for concept queries;
- learn fusion weights online;
- allow shadow planner decisions to affect answers;
- increase graph promotion capacity;
- add a new external infrastructure dependency.
