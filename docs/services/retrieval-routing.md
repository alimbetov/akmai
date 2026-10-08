# Retrieval planner and policy routing

## Purpose

This process converts analyzed `QueryChunk` values into an executable `RetrievalPlan` while preserving mandatory evidence lanes and providing fail-safe adaptive/canary routing.

The routing layer may reduce redundant work, but it must not make retrieval less safe when semantic analysis, policy lookup, or canary configuration is unavailable.

## Entry points

- `RetrievalPlanner#plan`
- `AdaptiveRetrievalPlanner#enforce`
- `AdaptiveRetrievalPlanner#shadow`
- `CanaryRetrievalRouter#route`
- `ApprovedRetrievalPolicyProvider`
- `ShadowRetrievalPolicyProvider`
- `CanaryRetrievalPolicyProvider`
- `ShadowRetrievalPlanBuilder`

## Process

1. `RetrievalPlanner` builds a deterministic baseline DAG per unique retrieval unit.
2. A semantic chunk without identifiers receives VECTOR, LEXICAL and CONCEPT roots plus REFERENCE depending on those roots.
3. A chunk with identifiers starts with IDENTIFIER. If semantic text also exists, VECTOR/LEXICAL/CONCEPT depend on IDENTIFIER and REFERENCE depends on the semantic lanes.
4. Equivalent retrieval units are deduplicated and step IDs are deterministic from canonical query content plus lane type.
5. If adaptive planning is enabled, `AdaptiveRetrievalPlanner` classifies the query and consults the APPROVED policy.
6. Missing/invalid policy or failed semantic analysis falls back to the baseline plan.
7. Approved policy lanes are intersected with baseline eligibility and mandatory lanes are restored: IDENTIFIER for identifier queries; VECTOR and REFERENCE for semantic queries.
8. Removed steps cause dependency lists to be normalized so no step depends on a removed predecessor.
9. `CanaryRetrievalRouter` may build a candidate plan from CANARY policy. If adaptive routing is disabled, policy is absent, or candidate equals production, production is returned unchanged.
10. When candidate differs, deterministic cohort sampling chooses CONTROL or CANARY; only CANARY executes the candidate plan.
11. HyDE is appended after adaptive/canary routing only for the original primary semantic query (`origin=ORIGINAL`, `index=0`) without identifiers and only if its VECTOR lane remains present.

## Business Rules

| ID | Rule | Enforcement |
|---|---|---|
| ROUTE-BR-01 | Baseline routing for ordinary semantic queries includes VECTOR, LEXICAL, CONCEPT and REFERENCE. | `RetrievalPlanner` |
| ROUTE-BR-02 | Identifier-only queries execute IDENTIFIER only. | `RetrievalPlanner` |
| ROUTE-BR-03 | Identifier + semantic queries execute IDENTIFIER first; semantic lanes depend on it. | `RetrievalPlanner` |
| ROUTE-BR-04 | REFERENCE depends on the applicable semantic discovery lanes. | `RetrievalPlanner` |
| ROUTE-BR-05 | Duplicate retrieval units do not multiply plan branches, and canonical content produces stable step IDs. | `RetrievalPlanner#unitKey`, `#step` |
| ROUTE-BR-06 | Adaptive routing is fail-safe: analyzer failure, missing APPROVED route, disabled planner or unusable plan returns the current/baseline plan. | `AdaptiveRetrievalPlanner#enforce` |
| ROUTE-BR-07 | An adaptive policy cannot remove IDENTIFIER from identifier queries. | `AdaptiveRetrievalPlanner#safePolicyLanes` |
| ROUTE-BR-08 | An adaptive policy cannot remove VECTOR or REFERENCE from a semantic query. | `AdaptiveRetrievalPlanner#safePolicyLanes` |
| ROUTE-BR-09 | Policy-requested lanes not eligible in the baseline cannot be introduced. | `safePolicyLanes` baseline intersection |
| ROUTE-BR-10 | Dependencies are normalized after lane pruning; no dangling dependency is allowed. | `AdaptiveRetrievalPlanner#enforce` |
| ROUTE-BR-11 | Shadow recommendations never alter the production plan. | `AdaptiveRetrievalPlanner#shadow` |
| ROUTE-BR-12 | Missing CANARY policy, disabled adaptive routing, empty input or unchanged candidate returns production unchanged and does not create a cohort decision. | `CanaryRetrievalRouter#route` |
| ROUTE-BR-13 | A changed canary candidate executes only for CANARY cohort; CONTROL executes production. | `CanaryRetrievalRouter#route` |
| ROUTE-BR-14 | Canary policy is subject to the same mandatory IDENTIFIER/VECTOR/REFERENCE safety lanes. | `CanaryRetrievalRouter#safePolicyLanes` |
| ROUTE-BR-15 | HyDE is selective: original primary semantic query only, no identifiers, and VECTOR must remain present. | `RetrievalPlanner#appendSelectiveHyde` |
| ROUTE-BR-16 | Multi-query variants and identifier queries do not receive an additional HyDE lane. | `RetrievalPlanner#appendSelectiveHyde` |

## Positive Cases

| ID | Preconditions | Action | Expected result | Tests |
|---|---|---|---|---|
| ROUTE-POS-01 | Semantic query, no identifiers | Build baseline plan | VECTOR + LEXICAL + CONCEPT + REFERENCE | `RetrievalPlannerTest#semanticQueryCreatesIndependentVectorAndLexicalRoots` |
| ROUTE-POS-02 | Identifier + semantic query | Build baseline plan | IDENTIFIER first; semantic lanes depend on it | `RetrievalPlannerTest#mixedIdentifierQueryMakesSemanticRetrievalDependOnIdentifierLookup` |
| ROUTE-POS-03 | Identifier-only query | Build plan | IDENTIFIER only | `RetrievalPlannerRoutingTest#identifierOnlyQueryUsesIdentifierLaneOnly` |
| ROUTE-POS-04 | Exact high-confidence concept | Adaptive enforcement | Redundant LEXICAL may be removed while VECTOR/CONCEPT/REFERENCE remain | `AdaptiveRetrievalPlannerTest#enforcementPrunesOnlyConfidentlyRedundantLaneAndNormalizesDependencies` |
| ROUTE-POS-05 | HyDE enabled; original primary semantic query; VECTOR present | Build plan | HYDE_VECTOR appended once | `RetrievalPlannerRoutingTest#hydeIsAddedOnlyForOriginalPrimarySemanticQueryWithVectorLane` |
| ROUTE-POS-06 | Canary candidate differs from production | Route deterministic request IDs | CONTROL uses production; CANARY uses candidate | `CanaryRetrievalRouterTest#canaryAndControlExecuteDifferentPlansForChangedCandidate` |

## Negative / fallback Cases

| ID | Preconditions / fault | Action | Expected behavior | Tests |
|---|---|---|---|---|
| ROUTE-NEG-01 | Semantic analyzer throws | Adaptive enforcement | Preserve full baseline; do not prune on missing evidence | `AdaptiveRetrievalPlannerTest#enforcementPreservesFullBaselineWhenSemanticAnalysisFails` |
| ROUTE-NEG-02 | APPROVED policy has no route for query class | Adaptive enforcement | Preserve baseline | `RetrievalPlannerRoutingTest#missingApprovedPolicyFallsBackToFullBaseline` |
| ROUTE-NEG-03 | APPROVED policy requests only LEXICAL for identifier+semantic query | Adaptive enforcement | Restore mandatory IDENTIFIER, VECTOR and REFERENCE; reject ineligible pruning | `RetrievalPlannerRoutingTest#unsafeApprovedPolicyCannotRemoveMandatoryIdentifierVectorOrReferenceLanes` |
| ROUTE-NEG-04 | Adaptive master switch disabled | Canary route | Production unchanged, no canary evidence | `CanaryRetrievalRouterTest#masterDisablePreventsCanaryRoutingAndEvidence` |
| ROUTE-NEG-05 | CANARY version absent | Canary route | Production unchanged, no observation | `CanaryRetrievalRouterFallbackTest#missingCanaryPolicyFallsBackToProductionWithoutObservation` |
| ROUTE-NEG-06 | CANARY policy produces same lane set as production | Canary route | Production unchanged, no cohort decision | `CanaryRetrievalRouterFallbackTest#unchangedCanaryCandidateFallsBackToProductionWithoutCohortDecision` |
| ROUTE-NEG-07 | Canary policy attempts to remove mandatory semantic lanes | Canary route | VECTOR and REFERENCE are restored | `CanaryRetrievalRouterTest#candidateCannotRemoveMandatorySemanticEvidenceLanes` |
| ROUTE-NEG-08 | HyDE enabled but query has identifier | Build plan | No HYDE_VECTOR | `RetrievalPlannerRoutingTest#hydeIsNotAddedForIdentifierQuery` |
| ROUTE-NEG-09 | HyDE enabled but chunk is a multi-query variant | Build plan | No HYDE_VECTOR | `RetrievalPlannerRoutingTest#hydeIsNotAddedForMultiQueryVariant` |

## Failure semantics

Routing failures must degrade to a safe production/baseline plan rather than to an empty plan. Semantic-analysis failure is treated as unavailable routing evidence, not as evidence that lanes should be removed. APPROVED/CANARY policy lookup is therefore conservative: absence or malformed policy must not narrow production retrieval.

The routing layer does not own database transactions for retrieval execution. Policy providers may read registry state, but the produced plan is immutable and execution occurs later in `ParallelRetrievalExecutor`.

## Observability

- Canary routing records a decision only when a candidate would actually change production execution.
- The decision records policy version, CONTROL/CANARY cohort and primary query class.
- Shadow planning provides recommendations without mutating production execution.
- Retrieval execution records actual per-step outcomes separately; routing decisions and execution failures must not be conflated.

## Tests

### Baseline planner

- `RetrievalPlannerTest`
- `RetrievalPlannerRoutingTest`

### Adaptive policy

- `AdaptiveRetrievalPlannerTest`
- `ApprovedAdaptiveRetrievalPolicyTest`
- `ShadowAdaptiveRetrievalPolicyTest`

### Canary routing

- `CanaryRetrievalRouterTest`
- `CanaryRetrievalRouterFallbackTest`

## Known gaps

- Policy training/promotion criteria are a separate process from runtime routing and should have their own service contract.
- End-to-end quality impact of lane selection remains governed by retrieval benchmark gates; unit tests prove routing invariants, not retrieval relevance quality.

## Change rule

Any change to query classification, mandatory lanes, lane dependencies, approved/shadow/canary policy behavior, cohort sampling or HyDE eligibility must update this contract and include both positive and negative/fallback tests in the same PR.
