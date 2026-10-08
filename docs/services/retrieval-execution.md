# Retrieval execution and degradation semantics

## Purpose

This process executes an immutable `RetrievalPlan` and converts every planned step into an explicit `RetrievalStepOutcome`. Its responsibility is to preserve usable partial evidence when safe, expose degraded execution, enforce dependency fail-closed rules, and distinguish recoverable lane loss from retrieval-wide critical failure.

The execution boundary is `ParallelRetrievalExecutor#executeDetailed`.

## Entry points

- `ParallelRetrievalExecutor#executeDetailed`
- `RetrievalPlan` / `RetrievalStep`
- `RetrievalStrategy#retrieve`
- `RetrievalStepOutcome`
- `RetrievalOutcomeStatus`
- `RetrievalExecutionResult`
- `PublishedLifecycleEligibility`
- `RetrievalObserver`

## Process

1. Normalize and validate the caller access-level scope.
2. Reject cyclic plan dependencies before scheduling work.
3. Schedule independent root steps concurrently and dependent steps only after their predecessors complete.
4. Execute each lane under both strategy and request deadlines.
5. Convert each terminal step into exactly one typed outcome: `SUCCESS`, `EMPTY`, `FAILED`, `TIMED_OUT`, `REJECTED`, or `SKIPPED_DEPENDENCY`.
6. Filter returned hits through current published-lifecycle eligibility before marking the step successful.
7. For identifier-scoped semantic retrieval, do not execute VECTOR/LEXICAL/CONCEPT when IDENTIFIER is empty or unavailable.
8. Execute REFERENCE only when at least one dependency produced usable hits; otherwise return `EMPTY` without invoking the strategy.
9. Aggregate hits only from step outcomes carrying hits.
10. Set `degraded=true` when any step outcome is not successful. `SUCCESS` and `EMPTY` are successful terminal states; all other statuses are degraded states.
11. Set `criticalFailure=true` when safe retrieval authority is lost: an identifier infrastructure failure, or simultaneous infrastructure failure of both VECTOR and LEXICAL for a non-identifier query.
12. Return `RetrievalExecutionResult` with all hits, per-step outcomes, degraded state and critical-failure state.

## Outcome model

| Status | Meaning | `successful()` | Typical category |
|---|---|---:|---|
| `SUCCESS` | Strategy completed with at least one eligible hit | yes | none |
| `EMPTY` | Strategy/dependency path completed normally but produced no eligible hits | yes | none |
| `FAILED` | Strategy or execution failed unexpectedly | no | exception class, `STRATEGY_MISSING`, `INTERRUPTED` |
| `TIMED_OUT` | Strategy/request/resource deadline or cancellation terminated the step | no | `STRATEGY_TIMEOUT`, `REQUEST_DEADLINE`, `RESOURCE_TIMEOUT`, `CANCELLED` |
| `REJECTED` | Executor rejected the work item | no | `EXECUTOR_REJECTED` |
| `SKIPPED_DEPENDENCY` | Step was intentionally not run because required scope/evidence dependency was unavailable | no | `IDENTIFIER_SCOPE_EMPTY`, `IDENTIFIER_SCOPE_UNAVAILABLE` |

## Business Rules

| ID | Rule | Enforcement |
|---|---|---|
| EXEC-BR-01 | Every planned step must have a typed terminal outcome; failures must not disappear into an empty hit list. | `ParallelRetrievalExecutor#executeDetailed` |
| EXEC-BR-02 | `SUCCESS` and `EMPTY` are normal successful terminal states; they do not by themselves mark execution degraded. | `RetrievalStepOutcome#successful` |
| EXEC-BR-03 | `FAILED`, `TIMED_OUT`, `REJECTED`, and `SKIPPED_DEPENDENCY` mark the aggregate execution degraded. | `RetrievalExecutionResult`, executor aggregation |
| EXEC-BR-04 | A lane exception is represented as `FAILED` and carries a failure category. | `outcomeFromFailure` |
| EXEC-BR-05 | Strategy/request/resource timeouts are represented as `TIMED_OUT`; timeout is not converted to `EMPTY`. | `outcomeFromFailure`, `timeout` |
| EXEC-BR-06 | Missing strategy implementation is `FAILED/STRATEGY_MISSING`. | `executeStep` |
| EXEC-BR-07 | Executor rejection is `REJECTED/EXECUTOR_REJECTED`. | `outcomeFromFailure` |
| EXEC-BR-08 | Identifier `EMPTY` is a valid scoped miss, but dependent VECTOR/LEXICAL/CONCEPT lanes must be skipped with `IDENTIFIER_SCOPE_EMPTY`; global semantic fallback is forbidden. | `dependencyDecision` |
| EXEC-BR-09 | Identifier infrastructure failure skips dependent semantic lanes with `IDENTIFIER_SCOPE_UNAVAILABLE` and makes the request critically unavailable. | `dependencyDecision`, `criticalFailure` |
| EXEC-BR-10 | A single VECTOR or LEXICAL infrastructure failure may be degraded but non-critical if the sibling primary lane remains usable. | `criticalFailure` |
| EXEC-BR-11 | Simultaneous infrastructure failure of VECTOR and LEXICAL for the same non-identifier query is critical. | `criticalFailure` |
| EXEC-BR-12 | CONCEPT, REFERENCE and HYDE_VECTOR failure alone is degraded but not sufficient for `criticalFailure=true`. | `criticalFailure` |
| EXEC-BR-13 | REFERENCE is not executed when none of its dependencies produced hits; it terminates as normal `EMPTY`. | `dependencyDecision` |
| EXEC-BR-14 | Lifecycle filtering occurs before final step status, so a strategy may execute successfully yet terminate as `EMPTY` after stale/ineligible hits are removed. | `PublishedLifecycleEligibility`, `executeStep` |
| EXEC-BR-15 | Step execution timeouts are measured after dependencies become ready; dependency wait must not consume the step's own strategy execution budget. | `StepExecution` scheduling |
| EXEC-BR-16 | Request deadline still bounds the whole retrieval plan and cancels unfinished work. | `executeDetailed` |

## Lane matrix

For `VECTOR`, `LEXICAL`, `CONCEPT`, `IDENTIFIER`, `REFERENCE`, and `HYDE_VECTOR`:

| Lane result | Step status | Aggregate degraded | Critical by itself |
|---|---|---:|---:|
| one or more eligible hits | `SUCCESS` | no | no |
| zero eligible hits | `EMPTY` | no | no |
| runtime exception | `FAILED` | yes | only `IDENTIFIER` |
| strategy timeout | `TIMED_OUT` | yes | only `IDENTIFIER` |
| executor rejection | `REJECTED` | yes | only `IDENTIFIER` |
| strategy missing | `FAILED` | yes | only `IDENTIFIER` |

For non-identifier semantic retrieval, VECTOR and LEXICAL form the minimum infrastructure pair: loss of one is degraded/non-critical; infrastructure loss of both is critical.

## Positive Cases

| ID | Preconditions | Action | Expected result | Tests |
|---|---|---|---|---|
| EXEC-POS-01 | Any supported lane returns eligible hits | Execute single-step plan | `SUCCESS`, hits preserved, `degraded=false`, `criticalFailure=false` | `ParallelRetrievalExecutorDegradationMatrixTest#successfulLaneProducesSuccessWithoutDegradation` |
| EXEC-POS-02 | Any supported lane returns no hits | Execute single-step plan | `EMPTY`, no hits, not degraded | `ParallelRetrievalExecutorDegradationMatrixTest#emptyLaneProducesEmptyWithoutDegradation` |
| EXEC-POS-03 | VECTOR fails but LEXICAL succeeds | Execute semantic plan | LEXICAL hits preserved; degraded but non-critical | `ParallelRetrievalExecutorDegradationMatrixTest#onePrimarySemanticLaneFailureIsDegradedButNotCritical` |
| EXEC-POS-04 | REFERENCE dependencies complete normally with no hits | Execute dependent plan | REFERENCE is not invoked and terminates `EMPTY`; execution is not degraded | `ParallelRetrievalExecutorDegradationMatrixTest#referenceWithoutSuccessfulDependencyHitsIsEmptyAndNotInvoked` |

## Negative / failure Cases

| ID | Preconditions / fault | Expected result | Tests |
|---|---|---|---|
| EXEC-NEG-01 | Any lane throws runtime exception | `FAILED`; failure category retained; aggregate degraded | `ParallelRetrievalExecutorDegradationMatrixTest#laneExceptionProducesTypedFailureAndDegradation` |
| EXEC-NEG-02 | Any lane exceeds strategy deadline | `TIMED_OUT`; aggregate degraded; identifier timeout is critical | `ParallelRetrievalExecutorDegradationMatrixTest#laneTimeoutProducesTypedTimeoutAndDegradation` |
| EXEC-NEG-03 | Planned lane has no registered strategy | `FAILED/STRATEGY_MISSING`; aggregate degraded | `ParallelRetrievalExecutorDegradationMatrixTest#missingStrategyProducesFailedOutcome` |
| EXEC-NEG-04 | IDENTIFIER returns empty | semantic dependents `SKIPPED_DEPENDENCY/IDENTIFIER_SCOPE_EMPTY`; no global fallback | `ParallelRetrievalExecutorDegradationMatrixTest#emptyIdentifierSkipsDependentSemanticLanesWithoutGlobalFallback` |
| EXEC-NEG-05 | IDENTIFIER fails | dependents skipped with unavailable category; execution critical | `ParallelRetrievalExecutorDegradationMatrixTest#identifierInfrastructureFailureIsCriticalAndSkipsDependents` |
| EXEC-NEG-06 | VECTOR and LEXICAL both fail infrastructurally | `criticalFailure=true` | `ParallelRetrievalExecutorDegradationMatrixTest#bothPrimarySemanticInfrastructureLanesFailCritically` |
| EXEC-NEG-07 | Executor queue rejects strategy work | `REJECTED/EXECUTOR_REJECTED` | `ParallelRetrievalExecutorRecoveryTest` |
| EXEC-NEG-08 | Request budget expires / cancellation occurs | unfinished work cancelled and typed timeout/cancellation outcome retained | `ParallelRetrievalExecutorCancellationTest`, `ParallelRetrievalExecutorTest` |
| EXEC-NEG-09 | Strategy returns stale/non-published hits | hits filtered before aggregation; terminal step may become `EMPTY` | `ParallelRetrievalExecutorLifecycleEligibilityTest` |

## Critical vs degraded semantics

`degraded` is an observability/quality signal: at least one planned step did not complete in a normal `SUCCESS`/`EMPTY` state. It does not automatically make the request unavailable.

`criticalFailure` is an authority/availability signal. It means the executor can no longer safely claim that the retrieval result represents the intended scope:

- identifier-scoped query: IDENTIFIER infrastructure failure is critical because the semantic lanes cannot safely expand globally;
- ordinary semantic query: both VECTOR and LEXICAL must suffer infrastructure failures before the result is critical;
- loss of optional/supplemental lanes (CONCEPT, REFERENCE, HYDE_VECTOR) alone is degraded but non-critical.

`RagQuestionService` converts `criticalFailure=true` to `RetrievalUnavailableException`; a merely degraded result may continue through fusion, reranking, context selection and grounding.

## Timeout and cancellation

Strategy timeout is per started strategy execution. Dependency wait does not consume that budget. The request timeout bounds the complete retrieval DAG. When the request budget is exhausted, unfinished executions are cancelled and represented explicitly rather than silently dropped.

Backends must still configure real JDBC/HTTP/socket timeouts. Executor cancellation is a coordination boundary; it is not a substitute for resource-level timeout configuration.

## Observability

- `RetrievalStepOutcome.status` gives the terminal state per step.
- `RetrievalStepOutcome.failureCategory` provides a bounded failure category.
- `RetrievalExecutionResult.degraded` distinguishes partial failure from fully normal execution.
- `RetrievalExecutionResult.criticalFailure` distinguishes availability failure from usable degraded evidence.
- `RetrievalObserver` records success/failure/outcome telemetry per retrieval type.

## Tests

- `ParallelRetrievalExecutorDegradationMatrixTest`
- `ParallelRetrievalExecutorTest`
- `ParallelRetrievalExecutorRecoveryTest`
- `ParallelRetrievalExecutorCancellationTest`
- `ParallelRetrievalExecutorLifecycleEligibilityTest`

## Known gaps

- This contract does not redefine lane-selection policy; see `retrieval-routing.md`.
- `GRAPH` is a `RetrievalType` but is not part of the current planner execution matrix documented here. If it becomes an independently planned execution lane, add explicit success/failure/criticality tests and update this contract before enabling it.
- Unit tests prove executor state transitions; repository/HTTP integration tests remain responsible for proving concrete JDBC/HTTP timeout enforcement.

## Change rule

Any change to `RetrievalOutcomeStatus`, dependency gating, timeout mapping, degraded aggregation or critical-failure rules must update this contract and the degradation matrix tests in the same PR.
