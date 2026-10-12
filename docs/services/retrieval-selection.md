# Retrieval fusion, reranking and context selection

## Purpose

This process transforms typed retrieval hits into the final context exposed to answer generation. It owns canonical fusion, optional reranking, knowledge/graph expansion, parent/temporal authority ordering, eligibility-aware context selection, bounded backfill and final publication/lifecycle revalidation.

The process preserves a usable baseline when optional quality enhancers fail, while failing closed when publication, access, lifecycle, temporal-authority or context-budget rules remove evidence.

## Process

1. `ResultFusion` rejects hits without routing identity or outside the caller access scope.
2. It resolves every accepted hit against the exact published `(accessLevel, documentId, generation, chunkId)` projection.
3. Duplicate hits for the same canonical key are collapsed and their retrieval evidence is accumulated; canonical published text is authoritative over snippets/conflicting hit payloads.
4. `Reranker` may reorder fused candidates. Timeout, executor rejection, scorer exception, non-finite scores or score-cardinality mismatch fall back to the original fused/RRF ordering.
5. `KnowledgeExpansion` and adaptive graph expansion may append evidence. Online graph expansion is fail-safe and returns existing candidates on runtime failure.
6. `ParentContextExpansion` runs before `TemporalAuthorityFilter`, so parent evidence introduced by expansion must independently pass document-status/effective-date authority.
7. `ContextSelectionCoordinator` synchronously applies canonical lifecycle/ACL/TTL eligibility to the candidate pool before any context slot is allocated.
8. `ContextDiversityFilter` and `ContextBudget` select from that eligible pool while preserving section, chunk-count, per-document, graph-count, serialized-token and chat-token limits.
9. `PublishedContextRevalidator` performs the final exact publication plus lifecycle/ACL/TTL TOCTOU fence.
10. If the final fence removes any selected identity, `ContextSelectionCoordinator` performs at most one rebuild/backfill round from the original eligible pool excluding the identities rejected by round one. Diversity and budget selection are recomputed from scratch.
11. The complete second selection is revalidated again. There is no third selection round; a second-race rejection yields a smaller safe context.
12. If no final context remains, generation does not run and the top-level flow returns insufficient information.

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
| SELECT-BR-10 | Parent expansion precedes temporal authority; newly materialized parent evidence cannot bypass `documentStatus`, `effectiveFrom` or `effectiveTo`. | `RagQuestionService`, `TemporalAuthorityFilter` |
| SELECT-BR-11 | Canonical `READY + ACTIVE + unexpired + ACL + published-generation` eligibility is applied before context budgeting. | `ContextSelectionCoordinator`, `PublishedLifecycleEligibility` |
| SELECT-BR-12 | Context selection is bounded by total chunks, per-document chunks, graph chunks, section diversity, serialized context tokens and optional chat-model budget. | `ContextDiversityFilter`, `ContextBudget` |
| SELECT-BR-13 | A single oversized candidate may be rejected, including the first candidate; budget exhaustion is a normal empty selection, not an infrastructure exception. | `ContextBudget` |
| SELECT-BR-14 | Final context is revalidated against exact publication state and lifecycle/ACL/TTL after retrieval/fusion/rerank/expansion and immediately before generation. | `PublishedContextRevalidator` |
| SELECT-BR-15 | If round-one final revalidation releases capacity, at most one deterministic backfill/rebuild round may refill it from lower-ranked eligible candidates. | `ContextSelectionCoordinator` |
| SELECT-BR-16 | Backfill reruns diversity and budget selection from scratch and revalidates the complete second selection; no incremental append may bypass quotas. | `ContextSelectionCoordinator` |
| SELECT-BR-17 | A second TOCTOU invalidation never starts a third round; the smaller safe context is authoritative. | `ContextSelectionCoordinator` |
| SELECT-BR-18 | If all evidence is removed by authority/budget/revalidation, the top-level flow returns insufficient information without answer generation. | `RagQuestionService` |

## Positive Cases

| ID | Preconditions | Expected result | Tests |
|---|---|---|---|
| SELECT-POS-01 | Same canonical chunk found by VECTOR and LEXICAL | One fused result with both evidence entries and weighted RRF score | `ResultFusionTest#rewardsEvidenceFromMultipleRetrievalChannels` |
| SELECT-POS-02 | Reranker returns valid finite scores | Candidates reorder while evidence/routing identity is preserved | `RerankerTest#semanticScoreImprovesRankingAndPreservesRetrievalEvidence` |
| SELECT-POS-03 | Published graph candidate remains eligible | Candidate may be appended as low-authority GRAPH evidence | `AdaptiveGraphOnlineExpansionTest#appendsRevalidatedGraphCandidateAfterExistingRetrieval` |
| SELECT-POS-04 | Context fits all limits and survives the final fence | Ordered candidates survive without a backfill round | `ContextBudgetTest`, `ContextSelectionCoordinatorTest#skipsBackfillWhenFirstSelectionSurvivesInOrder` |
| SELECT-POS-05 | Round-one final fence removes a selected hit while a lower-ranked candidate remains eligible | Rebuild selection once and fill released capacity without violating quotas | `ContextSelectionCoordinatorTest#backfillsOnceWhenFinalFenceRejectsSelectedCandidate` |

## Negative / Degraded Cases

| ID | Fault / race | Expected behavior | Tests |
|---|---|---|---|
| SELECT-NEG-01 | Duplicate hits contain conflicting snippets | Collapse duplicates and use canonical published payload; preserve both evidence channels | `ResultFusionContextFailureModelTest#duplicateConflictingHitsCollapseToCanonicalPublishedPayload` |
| SELECT-NEG-02 | Exact generation is no longer published during fusion | Drop stale hit; do not read/substitute current generation | `ResultFusionTest#staleGenerationIsDroppedInsteadOfReadingNewPublication` |
| SELECT-NEG-03 | Reranker timeout | Cancel task and return original RRF ordering | `RerankerTest#timeoutFallsBackToOriginalRrfOrdering`, `#timeoutInterruptsScoringTaskInsteadOfLeavingWorkerOccupied` |
| SELECT-NEG-04 | Reranker returns zero/wrong-cardinality scores | Preserve original ordering | `RerankerFallbackFailureModelTest` |
| SELECT-NEG-05 | Graph projection lookup throws or shadow graph report failed | Preserve pre-graph candidates; record degraded graph observation | `AdaptiveGraphOnlineExpansionFailureModelTest` |
| SELECT-NEG-06 | Parent has DRAFT/SUPERSEDED/future/expired/malformed temporal metadata | Parent is rejected after expansion and before context selection | `TemporalAuthorityFilter` tests, `RetrievalCorrectnessArchitectureTest` |
| SELECT-NEG-07 | Candidate or serialized provenance exceeds context token budget | Reject candidate; selection may become empty | `ContextBudgetTest#rejectsOversizedFirstChunk`, `ResultFusionContextFailureModelTest#contextBudgetExhaustionFailsClosedToEmptySelection` |
| SELECT-NEG-08 | Retrieved generation is unpublished before final revalidation | Drop it before generation | `PublishedContextRevalidatorTest`, `ResultFusionContextFailureModelTest` |
| SELECT-NEG-09 | Publication query still sees a row but lifecycle/TTL changes before the final lifecycle fence | Final lifecycle filter removes the hit | `ResultFusionContextFailureModelTest#finalLifecycleFenceCanRemoveHitAfterProjectionWasStillPublished` |
| SELECT-NEG-10 | A backfill candidate becomes stale before the second final fence | Remove it and return the smaller safe context; do not start a third round | `ContextSelectionCoordinatorTest#neverStartsThirdRoundWhenSecondFenceRejectsBackfill` |

## Failure semantics

Reranking and adaptive graph expansion are optional quality layers and therefore fail open to the last safe candidate ordering/set. Publication, ACL, generation identity, lifecycle, temporal authority and context-budget checks are authority/safety layers and therefore fail closed by removing ineligible evidence.

Database/transaction failures that escape fusion/context-selection are translated by `RagQuestionService` to retrieval unavailability. A legitimate empty result after authority/budget filtering is not an infrastructure failure; it produces an insufficient-information response without generation.

## Transaction and race boundary

The complete retrieval-selection pipeline is not wrapped in one database transaction. Exact publication state can change between initial retrieval and answer generation. The system therefore uses repeated exact-generation fences: candidate repositories enforce canonical eligibility before bounded lane truncation, fusion materializes published canonical projections, graph admission revalidates graph candidates, pre-budget selection filters lifecycle eligibility, and `PublishedContextRevalidator` performs the final publication + lifecycle/ACL/TTL check immediately before generation.

Backfill does not attempt to create a serializable snapshot. It is intentionally bounded to one rebuild round. The complete rebuilt selection crosses the final fence again; if the corpus changes again, the system accepts a smaller context rather than retrying indefinitely.

## Tests

- `ResultFusionTest`
- `WeightedResultFusionTest`
- `ResultFusionContextFailureModelTest`
- `RerankerTest`
- `RerankerFallbackFailureModelTest`
- `AdaptiveGraphOnlineExpansionTest`
- `AdaptiveGraphOnlineExpansionFailureModelTest`
- `ContextBudgetTest`
- `ContextSelectionCoordinatorTest`
- `PublishedContextRevalidatorTest`
- `RetrievalCorrectnessArchitectureTest`
- `RagQuestionServiceRetrievalFlowTest`

## Change rule

Any change to canonical fusion identity, duplicate evidence accumulation, reranker fallback, graph expansion failure behavior, parent/temporal ordering, context eligibility/backfill, context budget limits, publication/lifecycle revalidation ordering or stale-generation behavior must update this contract and include positive and negative/failure tests in the same PR.
