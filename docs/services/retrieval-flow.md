# Retrieval flow

## Purpose

The retrieval flow converts a user question plus an explicit access-level scope into either a grounded, cited answer or a fail-closed outcome.

The public orchestration boundary is `RagQuestionService.ask(...)`. The flow owns query analysis, retrieval planning/execution, fusion/reranking, graph and knowledge expansion, authority/context selection, answer generation, citation validation, deterministic/semantic grounding, and post-success learning signals.

## Entry points

- `kz.alimbetov.akmai.rag.service.RagQuestionService#ask`
- `kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner`
- `kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor`
- retrieval strategies implementing `RetrievalStrategy`
- candidate repositories for VECTOR / LEXICAL / CONCEPT / IDENTIFIER / REFERENCE
- `ResultFusion`
- `Reranker`
- `KnowledgeExpansion`
- adaptive graph shadow/online/competitive expansion
- `ParentContextExpansion`
- `TemporalAuthorityFilter`
- `ContextSelectionCoordinator`
- `ContextDiversityFilter`
- `ContextBudget`
- `PublishedContextRevalidator`
- `ContextAssembler`
- `AnswerGenerationService`
- `CitationValidator`
- `AnswerGroundingVerifier`
- optional `SemanticGroundingVerifier`

## Process

1. Reject an absent or empty access-level scope by returning the localized insufficient-information response before retrieval starts.
2. Split/analyze the question into `QueryChunk` values. Deterministic ORIGINAL decomposition is retained before any MULTI_QUERY expansion budget is consumed.
3. Build a `RetrievalPlan`.
4. Execute the plan through `ParallelRetrievalExecutor` under the request access-level scope. Candidate-producing VECTOR, LEXICAL, CONCEPT, IDENTIFIER and REFERENCE reads apply canonical `READY + ACTIVE + unexpired + exact ACL/generation` eligibility before their bounded `LIMIT/topK` becomes authoritative.
5. If execution reports a critical failure, terminate with `RetrievalUnavailableException`.
6. Fuse candidates and rerank them.
7. Expand knowledge and run adaptive graph shadow/online/competitive admission.
8. Expand parent context before applying temporal authority, so newly introduced parents cannot bypass document-status/effective-date rules.
9. `ContextSelectionCoordinator` applies synchronous lifecycle eligibility to the post-authority pool, then diversity and context budgets.
10. `PublishedContextRevalidator` performs the final exact publication/lifecycle/ACL/TTL TOCTOU fence. If that fence releases capacity, the coordinator may perform one bounded rebuild/backfill round and revalidates the complete rebuilt selection. No third round is allowed.
11. If no final context survives, return insufficient information without model generation.
12. Assemble context and generate an answer.
13. Validate citations. A blank answer or answer without valid cited sources is rejected.
14. Apply deterministic grounding. Unsupported or numerically inconsistent claims are rejected.
15. If semantic grounding is enabled, reject contradicted or insufficient semantic support.
16. Only after all grounding gates pass, record positive graph utility, grounded retrieval measurements and association-learning evidence.
17. Return the validated answer and cited sources.

## Business Rules

| ID | Rule | Enforcement |
|---|---|---|
| RET-BR-01 | Retrieval requires a non-empty caller access-level scope. No scope means fail closed before querying the corpus. | `RagQuestionService#askInternal` |
| RET-BR-02 | Every retrieval execution is scoped by the supplied access levels. | `ParallelRetrievalExecutor#executeDetailed` and strategies |
| RET-BR-03 | Canonical lifecycle eligibility is applied before bounded lane truncation; a stale/expired/INGESTING candidate cannot permanently consume a valid result slot. | candidate repositories, `PublishedLifecycleSql` |
| RET-BR-04 | Candidate-time eligibility does not replace late safety fences; existing lane eligibility and final context revalidation remain present. | retrieval executor/read wrappers, `PublishedContextRevalidator` |
| RET-BR-05 | Exact identifier misses remain fail-closed for dependent semantic lanes; correctness is restored by selecting only eligible identifier candidates before `LIMIT`, not by enabling global fallback. | `DocumentIdentifierRepository`, `ParallelRetrievalExecutor` |
| RET-BR-06 | A critical retrieval execution failure is availability failure, not an empty/insufficient answer. | `RagQuestionService#askInternal` |
| RET-BR-07 | Data-access or transaction failures during fusion/context selection are translated to `RetrievalUnavailableException`. | `RagQuestionService#askInternal` |
| RET-BR-08 | Parent expansion precedes temporal authority; parent evidence independently crosses document-status/effective-date rules. | `RagQuestionService`, `TemporalAuthorityFilter` |
| RET-BR-09 | Context selection applies lifecycle eligibility before budgeting and may perform at most one bounded backfill/rebuild after final TOCTOU invalidation. | `ContextSelectionCoordinator` |
| RET-BR-10 | The complete backfill selection is revalidated; a second race returns a smaller safe context and never creates a third retry round. | `ContextSelectionCoordinator` |
| RET-BR-11 | Empty final context terminates before generation. | `RagQuestionService#askInternal` |
| RET-BR-12 | Answers without at least one valid citation fail closed to insufficient information. | `CitationValidator`, `RagQuestionService` |
| RET-BR-13 | Deterministic grounding is mandatory after citation validation. | `AnswerGroundingVerifier` |
| RET-BR-14 | Semantic grounding, when configured, may reject an otherwise deterministically grounded answer. | `SemanticGroundingVerifier` |
| RET-BR-15 | Positive association learning is allowed only after citation and grounding gates accept the answer. | `RagQuestionService` |
| RET-BR-16 | Adaptive graph utility is recorded as unsuccessful for citation/grounding rejection and successful only for a grounded answer. | `AdaptiveGraphUtilityRecorder` |
| RET-BR-17 | Strategy timeouts/cancellation must not leave dependent retrieval work running without sufficient request budget. | `ParallelRetrievalExecutor` |
| RET-BR-18 | Partial non-critical strategy failure may produce a degraded execution, but critical failure must not be silently converted into a normal empty result. | `RetrievalExecutionResult`, `ParallelRetrievalExecutor` |

## Positive Cases

| ID | Preconditions | Action | Expected result | Tests |
|---|---|---|---|---|
| RET-POS-01 | Non-empty ACL scope; retrieval returns eligible context; generated answer contains a valid citation; grounding succeeds | Ask a question | Validated answer and cited source are returned | `RagQuestionServiceRetrievalFlowTest#groundedRetrievalFlowReturnsAnswerAndSource` |
| RET-POS-02 | Multiple retrieval strategies are eligible and complete inside their budgets | Execute plan | Strategy hits are collected and downstream plan dependencies can run | `ParallelRetrievalExecutorTest` |
| RET-POS-03 | A recoverable/non-critical strategy path fails while usable evidence remains | Execute plan | Execution remains observable as degraded rather than silently losing failure state | `ParallelRetrievalExecutorRecoveryTest` |
| RET-POS-04 | A higher-ranked candidate is lifecycle-ineligible and a lower-ranked candidate is eligible | Execute bounded lane | Eligible candidate remains recoverable inside the requested limit/topK | `CanonicalCandidateEligibilityIntegrationTest`, `PublishedVectorSearchIntegrationTest` |
| RET-POS-05 | Final fence removes a selected context hit but lower-ranked eligible evidence remains | Select context | One bounded rebuild/backfill round fills released capacity while preserving budgets | `ContextSelectionCoordinatorTest#backfillsOnceWhenFinalFenceRejectsSelectedCandidate` |
| RET-POS-06 | `maxExpandedChunks` is smaller than ORIGINAL decomposition | Analyze query | All ORIGINAL chunks remain; only generated variants are constrained by remaining expansion capacity | `QueryChunkerOriginalPreservationTest` |

## Negative Cases

| ID | Preconditions / fault | Action | Expected rejection / rollback | Tests |
|---|---|---|---|---|
| RET-NEG-01 | Access scope is empty | Ask a question | Return insufficient information; do not chunk, plan or retrieve | `RagQuestionServiceRetrievalFlowTest#missingAccessScopeFailsClosedBeforeRetrieval` |
| RET-NEG-02 | Executor reports `criticalFailure=true` | Ask a question | Throw `RetrievalUnavailableException`; do not fuse/rerank/generate | `RagQuestionServiceRetrievalFlowTest#criticalRetrievalFailureIsUnavailableAndStopsDownstreamPipeline` |
| RET-NEG-03 | DB/data-access failure occurs during post-retrieval context pipeline | Ask a question | Translate to `RetrievalUnavailableException`; do not generate an answer | `RagQuestionServiceRetrievalFlowTest#dataAccessFailureDuringContextPipelineIsWrappedAsUnavailable` |
| RET-NEG-04 | All candidates are removed by authority/budget/revalidation | Ask a question | Return insufficient information before generation | `RagQuestionServiceRetrievalFlowTest#emptySelectedContextReturnsInsufficientWithoutGeneration` |
| RET-NEG-05 | Generated answer has no valid cited sources | Validate answer | Return insufficient information; mark graph utility unsuccessful; skip deterministic grounding/association learning | `RagQuestionServiceRetrievalFlowTest#citationRejectionReturnsInsufficientAndSkipsGrounding` |
| RET-NEG-06 | Deterministic grounding reports unsupported evidence | Validate answer | Return insufficient information; no positive association learning | `RagQuestionServiceRetrievalFlowTest#deterministicGroundingRejectionReturnsInsufficient` |
| RET-NEG-07 | Semantic verifier reports contradiction | Validate answer | Return insufficient information; no positive association learning | `RagQuestionServiceRetrievalFlowTest#semanticContradictionReturnsInsufficientAndDoesNotLearnAssociations` |
| RET-NEG-08 | Strategy exceeds request/strategy budget or execution is cancelled | Execute retrieval plan | Cancel unfinished work and do not start dependent work without remaining budget | `ParallelRetrievalExecutorCancellationTest` |
| RET-NEG-09 | Retrieval candidate becomes lifecycle/publication ineligible after candidate admission | Execute/select context | Candidate is excluded before answer generation | `ParallelRetrievalExecutorLifecycleEligibilityTest`, `PublishedContextRevalidator` tests |
| RET-NEG-10 | Explicit parent temporal metadata is malformed, future, expired or inactive | Select context | Expanded parent fails closed before budgeting | `TemporalAuthorityFilter` tests, `RetrievalCorrectnessArchitectureTest` |
| RET-NEG-11 | A backfill candidate becomes stale during the second selection round | Select context | Remove it and return the smaller safe context; never run a third round | `ContextSelectionCoordinatorTest#neverStartsThirdRoundWhenSecondFenceRejectsBackfill` |

## Tests

### Orchestration and context selection

- `RagQuestionServiceTest`
- `RagQuestionServiceRetrievalFlowTest`
- `ContextSelectionCoordinatorTest`
- `RetrievalCorrectnessArchitectureTest`
- `ContextBudgetTest`
- `TemporalAuthorityFilter` tests
- `PublishedContextRevalidator` tests

### Retrieval execution and candidate correctness

- `ParallelRetrievalExecutorTest`
- `ParallelRetrievalExecutorRecoveryTest`
- `ParallelRetrievalExecutorCancellationTest`
- `ParallelRetrievalExecutorLifecycleEligibilityTest`
- `CanonicalCandidateEligibilityIntegrationTest`
- `PublishedVectorSearchIntegrationTest`
- `ReferenceGraphIntegrationTest`

### Query expansion

- `QueryChunkerTest`
- `QueryChunkerOriginalPreservationTest`

### Grounding

- `CitationValidator` tests
- `AnswerGroundingVerifier` tests
- semantic-grounding tests

## Transaction and failure boundary

Retrieval orchestration is read-oriented and does not wrap the complete flow in one database transaction. Individual repositories/strategies own bounded read transaction/query semantics. Candidate-time SQL, batched projection materialization and final lifecycle reads remain under the existing retrieval transaction timeout wrappers where those wrappers own the read path. `RagQuestionService` treats `DataAccessException` and `TransactionException` arising in fusion/context-selection as availability failures.

Generation and grounding happen after the final context is selected. Association-learning and positive graph-utility side effects happen only after answer grounding succeeds, preventing rejected answers from being learned as successful retrieval evidence.

## Timeout and cancellation

`ParallelRetrievalExecutor` owns strategy execution, timeout/cancellation and dependency scheduling. Strategy timeout must fit within the request budget. Unfinished tasks must be cancelled when their budget is exhausted; dependent work must not begin when insufficient request time remains.

## Observability

- `RagPipelineObserver` records pipeline-stage latency when configured.
- `MeasuredRetrievalCoordinator` records plan/stage/citation/grounded attribution when configured.
- `RetrievalExecutionResult` carries degraded/critical failure state and per-step outcomes.
- `RagLearningRecorder` records terminal answer/grounding outcome when configured.

## Known gaps

- Query routing/policy remains a separate service contract.
- `ChunkRole` searchability pushdown is not part of retrieval-correctness-hardening v1; existing searchable filtering/oversampling semantics remain unchanged.
- The additional intermediate lifecycle fences are intentionally retained in this correctness change. Removing redundant lifecycle round-trips requires separate benchmark-backed evidence.

## Change rule

Any PR that changes retrieval stage ordering, candidate lifecycle eligibility, access filtering, critical/degraded semantics, context admission/backfill, citation/grounding gates, timeout/cancellation behavior, or learning side effects must update this contract and add/update positive and negative tests in the same PR.
