# Retrieval flow

## Purpose

The retrieval flow converts a user question plus an explicit access-level scope into either a grounded, cited answer or a fail-closed outcome.

The public orchestration boundary is `RagQuestionService.ask(...)`. The flow owns query analysis, retrieval planning/execution, fusion/reranking, graph and knowledge expansion, authority/context selection, answer generation, citation validation, deterministic/semantic grounding, and post-success learning signals.

## Entry points

- `kz.alimbetov.akmai.rag.service.RagQuestionService#ask`
- `kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner`
- `kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor`
- retrieval strategies implementing `RetrievalStrategy`
- `ResultFusion`
- `Reranker`
- `KnowledgeExpansion`
- adaptive graph shadow/online/competitive expansion
- `TemporalAuthorityFilter`
- optional parent expansion and diversity filtering
- `ContextBudget`
- `PublishedContextRevalidator`
- `ContextAssembler`
- `AnswerGenerationService`
- `CitationValidator`
- `AnswerGroundingVerifier`
- optional `SemanticGroundingVerifier`

## Process

1. Reject an absent or empty access-level scope by returning the localized insufficient-information response before retrieval starts.
2. Split/analyze the question into `QueryChunk` values.
3. Build a `RetrievalPlan`.
4. Execute the plan through `ParallelRetrievalExecutor` under the request access-level scope.
5. If execution reports a critical failure, terminate with `RetrievalUnavailableException`.
6. Fuse candidates and rerank them.
7. Expand knowledge and run adaptive graph shadow/online/competitive admission.
8. Apply temporal authority filtering, optional parent expansion/diversity filtering, context budget, and final published-context revalidation.
9. If no final context survives, return insufficient information without model generation.
10. Assemble context and generate an answer.
11. Validate citations. A blank answer or answer without valid cited sources is rejected.
12. Apply deterministic grounding. Unsupported or numerically inconsistent claims are rejected.
13. If semantic grounding is enabled, reject contradicted or insufficient semantic support.
14. Only after all grounding gates pass, record positive graph utility, grounded retrieval measurements and association-learning evidence.
15. Return the validated answer and cited sources.

## Business Rules

| ID | Rule | Enforcement |
|---|---|---|
| RET-BR-01 | Retrieval requires a non-empty caller access-level scope. No scope means fail closed before querying the corpus. | `RagQuestionService#askInternal` |
| RET-BR-02 | Every retrieval execution is scoped by the supplied access levels. | `ParallelRetrievalExecutor#executeDetailed` and strategies |
| RET-BR-03 | A critical retrieval execution failure is availability failure, not an empty/insufficient answer. | `RagQuestionService#askInternal` |
| RET-BR-04 | Data-access or transaction failures during fusion/context selection are translated to `RetrievalUnavailableException`. | `RagQuestionService#askInternal` |
| RET-BR-05 | Empty final context terminates before generation. | `RagQuestionService#askInternal` |
| RET-BR-06 | Context is revalidated against current publication/access state immediately before generation. | `PublishedContextRevalidator` |
| RET-BR-07 | Answers without at least one valid citation fail closed to insufficient information. | `CitationValidator`, `RagQuestionService` |
| RET-BR-08 | Deterministic grounding is mandatory after citation validation. | `AnswerGroundingVerifier` |
| RET-BR-09 | Semantic grounding, when configured, may reject an otherwise deterministically grounded answer. | `SemanticGroundingVerifier` |
| RET-BR-10 | Positive association learning is allowed only after citation and grounding gates accept the answer. | `RagQuestionService` |
| RET-BR-11 | Adaptive graph utility is recorded as unsuccessful for citation/grounding rejection and successful only for a grounded answer. | `AdaptiveGraphUtilityRecorder` |
| RET-BR-12 | Temporal/versioned knowledge is fail closed when explicit status/effective-date metadata is malformed or inactive. | `TemporalAuthorityFilter` |
| RET-BR-13 | Strategy timeouts/cancellation must not leave dependent retrieval work running without sufficient request budget. | `ParallelRetrievalExecutor` |
| RET-BR-14 | Partial non-critical strategy failure may produce a degraded execution, but critical failure must not be silently converted into a normal empty result. | `RetrievalExecutionResult`, `ParallelRetrievalExecutor` |

## Positive Cases

| ID | Preconditions | Action | Expected result | Tests |
|---|---|---|---|---|
| RET-POS-01 | Non-empty ACL scope; retrieval returns eligible context; generated answer contains a valid citation; grounding succeeds | Ask a question | Validated answer and cited source are returned | `RagQuestionServiceRetrievalFlowTest#groundedRetrievalFlowReturnsAnswerAndSource` |
| RET-POS-02 | Multiple retrieval strategies are eligible and complete inside their budgets | Execute plan | Strategy hits are collected and downstream plan dependencies can run | `ParallelRetrievalExecutorTest` |
| RET-POS-03 | A recoverable/non-critical strategy path fails while usable evidence remains | Execute plan | Execution remains observable as degraded rather than silently losing failure state | `ParallelRetrievalExecutorRecoveryTest` |
| RET-POS-04 | Current publication/authority metadata is valid | Select context | Eligible context survives revalidation and may be used for generation | existing `PublishedContextRevalidator` / `TemporalAuthorityFilter` tests |

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
| RET-NEG-09 | Retrieval candidate is no longer lifecycle/publication eligible | Execute/select context | Candidate is excluded before answer generation | `ParallelRetrievalExecutorLifecycleEligibilityTest` and revalidator tests |
| RET-NEG-10 | Explicit temporal metadata is malformed, future, expired, or inactive | Select context | Candidate fails closed | `TemporalAuthorityFilter` tests |

## Tests

### Orchestration

- `src/test/java/kz/alimbetov/akmai/rag/service/RagQuestionServiceTest.java`
- `src/test/java/kz/alimbetov/akmai/rag/service/RagQuestionServiceRetrievalFlowTest.java`

### Retrieval execution and failure model

- `ParallelRetrievalExecutorTest`
- `ParallelRetrievalExecutorRecoveryTest`
- `ParallelRetrievalExecutorCancellationTest`
- `ParallelRetrievalExecutorLifecycleEligibilityTest`

### Strategy-level coverage

- `ReferenceRetrievalStrategyTest`
- `ConceptRetrievalStrategyTest`
- `IdentifierRetrievalStrategyTest`
- vector/lexical/HyDE strategy tests where present

### Grounding/context coverage

- `CitationValidator` tests
- `AnswerGroundingVerifier` tests
- `TemporalAuthorityFilter` tests
- `PublishedContextRevalidator` tests
- semantic-grounding tests

## Transaction and failure boundary

Retrieval orchestration is read-oriented and does not wrap the complete flow in one database transaction. Individual repositories/strategies own bounded read transaction/query semantics. `RagQuestionService` treats `DataAccessException` and `TransactionException` arising in the fusion/context-selection section as availability failures.

Generation and grounding happen after the final context is selected. Association-learning and positive graph-utility side effects happen only after answer grounding succeeds, preventing rejected answers from being learned as successful retrieval evidence.

## Timeout and cancellation

`ParallelRetrievalExecutor` owns strategy execution, timeout/cancellation and dependency scheduling. Strategy timeout must fit within the request budget. Unfinished tasks must be cancelled when their budget is exhausted; dependent work must not begin when insufficient request time remains.

## Observability

- `RagPipelineObserver` records pipeline-stage latency when configured.
- `MeasuredRetrievalCoordinator` records plan/stage/citation/grounded attribution when configured.
- `RetrievalExecutionResult` carries degraded/critical failure state and per-step outcomes.
- `RagLearningRecorder` records terminal answer/grounding outcome when configured.

## Known gaps

- This contract covers the top-level retrieval/answer orchestration and its failure gates. Retrieval policy/routing behavior remains a separate service contract and must be documented from `RetrievalPlanner`/adaptive policy implementation.
- Integration coverage should continue to verify ACL and lifecycle filtering against real PostgreSQL state; mocked orchestration tests are not a substitute for repository-level isolation tests.

## Change rule

Any PR that changes retrieval stage ordering, access filtering, critical/degraded semantics, context admission, citation/grounding gates, timeout/cancellation behavior, or learning side effects must update this contract and add/update both positive and negative tests in the same PR.
