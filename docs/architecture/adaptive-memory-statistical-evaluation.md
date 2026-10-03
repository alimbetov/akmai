# Adaptive memory statistical evaluation

## Objective

AkmAI must evaluate Adaptive Chunk Graph and future Memory Router policies with
paired, reproducible experiments rather than selecting a policy from intuition or
a single aggregate score.

This protocol covers offline replay first and canary evaluation second.

## Decision hierarchy

The final decision is lexicographic, not a weighted popularity contest.

1. **Security gate** — ACL violation count must be exactly zero.
2. **Correctness gate** — stale generation / lifecycle violations must be zero.
3. **Quality gate** — candidate must be non-inferior on primary retrieval quality
   and preferably show positive lift.
4. **Latency/capacity gate** — p95/p99 and storage/maintenance stay inside the
   approved SLO/capacity envelope.
5. **Statistical evidence** — paired confidence intervals and corrected pairwise
   tests support the observed difference.
6. **Pareto choice** — among statistically defensible candidates, prefer the
   simplest policy on the quality/latency/cost frontier.

Do not collapse these gates into one arbitrary composite score for production
promotion.

## Golden set

Use the same query against every strategy (paired/repeated-measures design).

The set must be stratified by:

- language: kk, ru, en, zh, de, fr, es, pt, it, tr, el;
- sector / ontology family;
- query type: identifier, semantic, mixed, reference-heavy;
- difficulty;
- single-domain and multi-domain questions.

The exact sample size is determined from pilot paired differences and target
minimum detectable effect, not from one assumed global standard deviation.

Ground truth should include relevant chunk identities and, where practical,
graded relevance rather than binary relevance only.

For manually annotated sets, report inter-annotator agreement and adjudicate
material disagreements.

## Primary metrics

Retrieval quality:

- nDCG@10 for graded ordering quality;
- Recall@10;
- MRR for first useful hit;
- graph expansion precision;
- citation-supported expansion lift.

Operational quality:

- end-to-end retrieval p50/p95/p99;
- graph lookup p50/p95/p99;
- candidates admitted to rerank;
- context token count;
- graph bytes per active chunk;
- maintenance throughput and backlog.

Safety:

- ACL violations = 0;
- stale-generation hits = 0;
- unpublished/retired target hits = 0.

## Paired experiment design

For every query q and strategy s:

    result[q, s] = run(strategy=s, query=q, fixed_snapshot=true)

All strategies use the same:

- corpus snapshot;
- ACL set;
- embedding profile;
- ontology version;
- graph version;
- configuration except the policy under test.

Randomize strategy execution order using a fixed seed and warm relevant caches
before timed repetitions.

Never compare strategies on different document generations.

## Statistical analysis

### 1. Omnibus test

When comparing more than two strategies on the same queries, use the Friedman
test on the primary per-query metric.

It answers whether at least one strategy differs from the others under the
paired design.

### 2. Pairwise post-hoc

If the omnibus test is significant, compare candidate pairs with Wilcoxon
signed-rank tests on per-query paired differences.

Correct the family of pairwise p-values with Holm's method.

Holm is preferred here over an uncorrected all-pairs analysis and avoids relying
on a single global ranking statistic as the final decision.

### 3. Effect size for paired data

Report matched-pairs rank-biserial correlation (or an equivalent paired effect
size) for each important comparison.

Do not use Cliff's delta as the primary effect size for this paired design; it
targets independent-sample ordering rather than the matched query differences
that AkmAI actually observes.

### 4. Confidence intervals

For each important delta, report a paired bootstrap confidence interval over
queries:

    delta_q = metric(candidate, q) - metric(baseline, q)

Bootstrap queries as paired units, preserving all strategy outcomes for a query.

For language/sector reporting, use stratified paired bootstrap so large strata do
not silently erase weak performance in smaller supported languages.

## Stratified reporting

Always report global and slice results.

Required slices:

- language;
- sector/facet;
- query type;
- difficulty;
- identifier-present vs semantic-only;
- graph-originated vs non-graph-originated expansion.

A candidate is not production-eligible if the global average improves by hiding
a material regression in a protected operational slice such as ACL routing or a
supported language.

## Multiple metrics

The primary metric is declared before running the final evaluation.

Secondary metrics describe tradeoffs and diagnose failure modes.

Do not fish across many metrics and then promote whichever produced the smallest
p-value.

For production selection:

- security/correctness are hard gates;
- primary quality is the statistical decision metric;
- latency and capacity are SLO gates;
- secondary metrics explain why.

## Baselines for Adaptive Chunk Graph

At minimum compare:

A. graph disabled;
B. HOT-only one-hop expansion;
C. HOT + bounded WARM allowance;
D. candidate policy under calibration.

Future Memory Router strategies can be added to the same paired harness, but the
Adaptive Graph baseline remains graph-disabled hybrid retrieval.

## Banded graph evaluation

Because AkmAI uses HOT/WARM/CANDIDATE quotas, collect per-band outcomes:

- edges read;
- edges admitted after ontology compatibility;
- edges surviving rerank;
- edges entering bounded context;
- edges cited;
- edges promoted/demoted/evicted.

Key conditional rates:

    hot_citation_rate
    warm_incremental_recall
    candidate_promotion_precision
    eviction_regret

Eviction regret is the fraction of recently evicted edges that would soon have
qualified for promotion or produced useful expansion. High eviction regret means
the band quota is too small or promotion is too slow.

## Online canary

Offline statistical significance is necessary but not sufficient.

Canary evaluation uses a predeclared horizon and success criteria.

Measure:

- citation-supported answer success;
- retrieval latency;
- graph lookup latency;
- expansion acceptance;
- fallback/insufficient-information rate;
- ACL/lifecycle violations;
- graph growth and maintenance backlog.

Do not continuously peek and stop the canary at the first favourable p-value.
Use a fixed horizon or a formally designed sequential test.

## Runtime telemetry

Micrometer metrics use fixed low-cardinality tags only.

Graph metrics include:

    akmai.adaptive.graph.edges{band=...}
    akmai.adaptive.graph.learning{signal=...,outcome=...}
    akmai.adaptive.graph.lookup{band=...}
    akmai.adaptive.graph.lookup.candidates{band=...}
    akmai.adaptive.graph.expansion{outcome=...}
    akmai.adaptive.graph.band.transition{from=...,to=...}
    akmai.adaptive.graph.maintenance{outcome=...}
    akmai.adaptive.graph.maintenance.processed
    akmai.adaptive.graph.maintenance.evicted
    akmai.adaptive.graph.maintenance.backlog

Do not put query text, document ID, chunk ID, user ID, ontology concept ID or
other high-cardinality identifiers in metric tags.

Those belong in bounded diagnostic logs/traces or offline experiment artifacts.

## Promotion record

Every promoted policy/configuration records:

- corpus snapshot;
- golden-set version;
- graph and ontology versions;
- tested policy ID;
- primary metric;
- paired delta and confidence interval;
- corrected pairwise test result;
- effect size;
- latency/capacity results;
- slice regressions;
- security result;
- decision.

Decision:

    INSUFFICIENT_DATA
    REJECTED
    CANARY_ELIGIBLE
    APPROVED

This makes Adaptive Graph evolution reproducible instead of anecdotal.
