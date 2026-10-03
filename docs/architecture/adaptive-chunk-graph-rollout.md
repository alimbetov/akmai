# Adaptive Chunk Graph rollout

## Objective

Introduce retrieval memory without weakening AkmAI's current ACL, generation,
retention, reference-authority or hybrid-search guarantees.

## Phase 0 — Architecture contract

Deliverables:

- this architecture;
- security/lifecycle invariants;
- benchmark dataset;
- feature flags;
- migration/rollback design.

No runtime behaviour changes.

## Phase 1 — Storage and repository, disabled

Implement:

- Liquibase association parent table;
- ACL LIST partition provisioning;
- fixed HASH subpartitions;
- source adjacency index;
- lifecycle/repair deletion;
- repository APIs requiring non-empty ACL sets;
- transactional symmetric edge writes.

Feature remains disabled.

Tests:

- no cross-ACL edge can be inserted;
- missing ACL is rejected;
- generation identity is enforced;
- partition pruning is demonstrated;
- retirement removes every edge for a retired generation;
- repair detects/removes orphan payload.

## Phase 2 — Shadow learning

Add `AssociationLearningRecorder` after citation validation.

Record learning evidence but do not use graph neighbours in retrieval.

Measure:

- edge creation rate;
- degree distribution;
- duplicate-query suppression;
- active/candidate ratio;
- storage growth;
- maintenance cost;
- top hubs;
- ACL distribution.

No user-visible retrieval change.

## Phase 3 — Maintenance and bounded activation

Add bounded maintenance:

- evidence aggregation;
- weight recomputation;
- decay;
- CANDIDATE -> ACTIVE;
- ACTIVE -> DECAYED;
- max-degree enforcement;
- purge.

Graph still does not affect answers.

Quality gate: stable storage growth under replay/load tests.

## Phase 4 — Shadow expansion

For strong reranked seeds, query ACTIVE graph neighbours and compute candidate
scores, but do not add them to context.

Compare shadow graph candidates against:

- later citations;
- exact/reference hits;
- reranker scores;
- oracle/golden retrieval cases.

Quality gate: graph candidates show positive precision before activation.

## Phase 5 — Canary one-hop expansion

Enable graph expansion for a small controlled share of requests.

Constraints:

- one hop only;
- top-N strong seeds;
- strict per-request neighbour quota;
- canonical ACTIVE/PUBLISHED target revalidation;
- ontology compatibility if available;
- graph candidates remain low authority;
- context budget is unchanged.

Primary success metric:

```text
citation/answer-quality lift
```

not context size.

Rollback is a feature flag.

## Phase 6 — Semantic Intelligence integration

Integrate the versioned 11-language lexicon and ontology:

- query concept profile;
- chunk semantic annotations;
- sector/facet compatibility;
- bounded same-language lexical expansion;
- ontology-aware association filtering.

Global vector retrieval stays enabled.

## Phase 7 — Production hardening

Required evidence:

- exact-head CI;
- ACL security tests;
- lifecycle/retention tests;
- concurrency tests for symmetric edge upserts;
- 100k / 1M / 5M edge storage benchmarks;
- lookup p50/p95/p99;
- maintenance throughput;
- WAL/bloat/autovacuum observation;
- hubness/degree metrics;
- graph-disabled versus graph-enabled retrieval quality;
- DR/rebuild procedure.

## Feature flags

Suggested independent switches:

```text
akmai.adaptive-graph.learning-enabled
akmai.adaptive-graph.maintenance-enabled
akmai.adaptive-graph.shadow-expansion-enabled
akmai.adaptive-graph.expansion-enabled
```

Learning and online expansion must be independently disableable.

## Repository API shape

No unscoped lookup API is allowed.

Conceptual contract:

```java
List<AssociationCandidate> findRelated(
        Set<Long> allowedAccessLevels,
        ChunkGenerationIdentity source,
        int limit
);
```

Writes require a single ACL because learned edges cannot cross ACL boundaries.

## Expansion defaults

Initial behaviour should be deliberately conservative:

```text
one graph hop
few seed chunks
few neighbours per seed
few admitted graph candidates
strict minimum active weight
no graph-only retrieval
```

Exact values are selected by benchmark and configuration validation.

## Quality failure conditions

Do not enable graph expansion in production if any of these are observed:

- cross-ACL routing ambiguity;
- graph-expanded stale generation payload;
- graph candidates displacing exact references/identifiers;
- runaway high-degree hubs;
- unbounded edge growth;
- lower citation precision;
- degraded p95/p99 retrieval latency beyond agreed SLO;
- maintenance backlog that grows continuously;
- inability to rebuild or purge graph state deterministically.

## Rebuild model

The association graph is derived retrieval memory, not canonical knowledge.

AkmAI must remain correct if the entire adaptive graph is lost.

Recovery:

```text
disable graph expansion
 -> continue VECTOR/LEXICAL/IDENTIFIER/REFERENCE retrieval
 -> rebuild learned graph gradually from future validated evidence
```

No answer correctness guarantee may depend exclusively on adaptive associations.
