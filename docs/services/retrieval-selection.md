# Retrieval fusion, reranking and context selection

## Purpose

This process transforms typed retrieval hits into the final context exposed to answer generation. It owns canonical fusion, optional reranking, knowledge/graph expansion, authority filtering, context budgeting and final publication/lifecycle revalidation.

The process must preserve a usable baseline when optional quality enhancers fail, while failing closed when publication, access, lifecycle or context-budget authority removes all evidence.

## Process

1. `ResultFusion` rejects hits without routing identity or outside the caller access scope.
2. It resolves every accepted hit against the exact published `(accessLevel, documentId, generation, chunkId)` projection.
3. Duplicate hits for the same canonical key are collapsed and their retrieval evidence is accumulated; canonical published text is authoritative over snippets/conflicting hit payloads.
4. `Reranker` may reorder the fused candidates. Timeout, executor rejection, scorer exception, non-finite scores or score-cardinality mismatch fall back to the original fused/RRF ordering.
5. `KnowledgeExpansion` and adaptive graph expansion may append evidence. Online graph expansion is fail-safe and returns existing candidates on runtime failure.
6. Temporal authority, parent expansion and diversity filtering are applied when configured.
7. `ContextBudget` admits candidates only while chunk-count, per-document, graph-count, serialized-token and chat-token budgets remain valid.
8. `PublishedContextRevalidator` performs the final exact-generation publication check immediately before answer generation.
9. After publication materialization, lifecycle/ACL/TTL eligibility is evaluated as the last synchronous database fence to close the TOCTOU window as far as possible.
10. If no final context remains, generation must not run; the top-level retrieval flow returns insufficient information.

## Business Rules

| ID | Rule | Enforcement |
|---|---|---|
| SELECT-BR-01 | Fusion identity is exact `(accessLevel, documentId, generation, chunkId)`; same chunk IDs in different documents/generations are not interchangeable. | `ResultFusion` |
| SELECT-BR-02 | Duplicate evidence for one canonical key is collapsed into one result and retains all contributing retrieval evidence. | `ResultFusion.Accumulator` |
| SELECT-BR-03 | Canonical published projection text/metadata is authoritative over conflicting retrieval snippets. | `ResultFusion#canonicalHit` |
| SELECT-BR-04 | A hit whose exact generation is no longer published is dropped; fusion must not silently substitute a newer generation. | `ResultFusion#canonicalProjections` |
| SELECT-BR-05 | Reranking is an optional quality enhancer. Failure or timeout preserves original fused ordering. | `Reranker#rerank` |
| SELECT-BR-06 | Empty or cardinality-mismatched reranker scores are invalid scorer output and must fall back to original ordering. | `Reranker#score`, `#rerank` |
| SELECT-BR-07 | Authority tier remains dominant over semantic rerank score. | `Reranker#score` |
| SELECT-BR-08 | Adaptive online graph expansion failure must preserve existing candidates and emit failure observability. | `AdaptiveGraphOnlineExpansion#expand` |
| SELECT-BR-09 | Graph candidates are revalidated against exact published projections before admission. | `AdaptiveGraphOnlineExpansion` |
| SELECT-BR-10 | Context selection is bounded by total chunks, per-document chunks, graph chunks, serialized context tokens and optional chat-model budget. | `ContextBudget` |
| SELECT-BR-11 | A single oversized candidate may be rejected, including the first candidate; budget exhaustion is a normal empty selection, not an infrastructure exception. | `ContextBudget` |
| SELECT-BR-12 | Final context is revalidated against exact publication state after retrieval/fusion/rerank/expansion and immediately before generation. | `PublishedContextRevalidator` |
| SELECT-BR-13 | Publication disappearance between retrieval and generation removes the stale hit instead of replacing it with a newer generation. | `PublishedContextRevalidator` |
| SELECT-BR-14 | Lifecycle/ACL/TTL filtering runs after publication-key materialization as the final synchronous fence, preventing a stale/expired row from reaching generation when the final lifecycle check rejects it. | `PublishedContextRevalidator` |
| SELECT-BR-15 | If all evidence is removed by budgeting or revalidation, the top-level flow returns insufficient information without answer generation. | `RagQuestionService` |

## Positive Cases

| ID | Preconditions | Expected result | Tests |
|---|---|---|---|
| SELECT-POS-01 | Same canonical chunk found by VECTOR and LEXICAL | One fused result with both evidence entries and weighted RRF score | `ResultFusionTest#rewardsEvidenceFromMultipleRetrievalChannels` |
| SELECT-POS-02 | Reranker returns valid finite scores | Candidates reorder while evidence/routing identity is preserved | `RerankerTest#semanticScoreImprovesRankingAndPreservesRetrievalEvidence` |
| SELECT-POS-03 | Published graph candidate remains eligible | Candidate may be appended as low-authority GRAPH evidence | `AdaptiveGraphOnlineExpansionTest#appendsRevalidatedGraphCandidateAfterExistingRetrieval` |
| SELECT-POS-04 | Context fits all limits | Ordered candidates survive into final selection | `ContextBudgetTest` |

## Negative / Degraded Cases

| ID | Fault / race | Expected behavior | Tests |
|---|---|---|---|
| SELECT-NEG-01 | Duplicate hits contain conflicting snippets | Collapse duplicates and use canonical published payload; preserve both evidence channels | `ResultFusionContextFailureModelTest#duplicateConflictingHitsCollapseToCanonicalPublishedPayload` |
| SELECT-NEG-02 | Exact generation is no longer published during fusion | Drop stale hit; do not read/substitute current generation | `ResultFusionTest#staleGenerationIsDroppedInsteadOfReadingNewPublication` |
| SELECT-NEG-03 | Reranker timeout | Cancel task and return original RRF ordering | `RerankerTest#timeoutFallsBackToOriginalRrfOrdering`, `#timeoutInterruptsScoringTaskInsteadOfLeavingWorkerOccupied` |
| SELECT-NEG-04 | Reranker returns zero scores for non-empty candidates | Treat as score-count mismatch and preserve original ordering | `RerankerFallbackFailureModelTest#emptyScorerOutputFallsBackToOriginalOrdering` |
| SELECT-NEG-05 | Reranker returns wrong score count | Preserve original ordering | `RerankerFallbackFailureModelTest#scorerCardinalityMismatchFallsBackToOriginalOrdering` |
| SELECT-NEG-06 | Graph projection lookup throws | Record `online_failed` and preserve pre-graph candidates | `AdaptiveGraphOnlineExpansionFailureModelTest#projectionFailureFallsBackToExistingCandidates` |
| SELECT-NEG-07 | Shadow graph report is failed | Do not query projection storage; preserve existing candidates | `AdaptiveGraphOnlineExpansionFailureModelTest#failedShadowReportFallsBackWithoutProjectionRead` |
| SELECT-NEG-08 | Candidate or serialized provenance exceeds context token budget | Reject candidate; selection may become empty | `ContextBudgetTest#rejectsOversizedFirstChunk`, `ResultFusionContextFailureModelTest#contextBudgetExhaustionFailsClosedToEmptySelection` |
| SELECT-NEG-09 | Retrieved generation is unpublished before final revalidation | Drop it before generation | `PublishedContextRevalidatorTest#dropsGenerationThatIsNoLongerPublishedBeforeAnswerUse`, `ResultFusionContextFailureModelTest#unpublishedGenerationIsDroppedBeforeFinalLifecycleFence` |
| SELECT-NEG-10 | Publication query still sees the row but lifecycle/TTL changes before the final lifecycle fence | Final lifecycle filter removes the hit | `ResultFusionContextFailureModelTest#finalLifecycleFenceCanRemoveHitAfterProjectionWasStillPublished` |

## Failure semantics

Reranking and adaptive graph expansion are optional quality layers and therefore fail open to the last safe candidate ordering/set. Publication, ACL, generation identity, lifecycle and context-budget checks are authority/safety layers and therefore fail closed by removing ineligible evidence.

Database/transaction failures that escape fusion/context-selection are translated by `RagQuestionService` to retrieval unavailability. A legitimate empty result after authority/budget filtering is not an infrastructure failure; it produces an insufficient-information response without generation.

## Transaction and race boundary

The complete retrieval-selection pipeline is not wrapped in one database transaction. Exact publication state can change between initial retrieval and answer generation. The system therefore uses repeated exact-generation fences: fusion materializes published canonical projections, graph admission revalidates graph candidates, and `PublishedContextRevalidator` performs the final publication + lifecycle/ACL/TTL check immediately before generation.

This does not create a global serializable snapshot. It intentionally provides a final synchronous authority fence close to generation and rejects evidence that became stale during the request.

## Tests

- `ResultFusionTest`
- `WeightedResultFusionTest`
- `ResultFusionContextFailureModelTest`
- `RerankerTest`
- `RerankerFallbackFailureModelTest`
- `AdaptiveGraphOnlineExpansionTest`
- `AdaptiveGraphOnlineExpansionFailureModelTest`
- `ContextBudgetTest`
- `PublishedContextRevalidatorTest`
- `RagQuestionServiceRetrievalFlowTest`

## Change rule

Any change to canonical fusion identity, duplicate evidence accumulation, reranker fallback, graph expansion failure behavior, context budget limits, publication/lifecycle revalidation ordering or stale-generation behavior must update this contract and include positive and negative/failure tests in the same PR.
