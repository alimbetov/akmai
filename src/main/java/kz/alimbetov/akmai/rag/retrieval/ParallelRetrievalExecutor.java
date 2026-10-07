package kz.alimbetov.akmai.rag.retrieval;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ParallelRetrievalExecutor {

    private final Map<RetrievalType, RetrievalStrategy> strategies;
    private final Executor retrievalExecutor;
    private final RetrievalObserver observer;
    private final RetrievalProperties properties;
    private final PublishedLifecycleEligibility lifecycleEligibility;

    public ParallelRetrievalExecutor(
            List<RetrievalStrategy> strategies,
            @Qualifier("retrievalExecutor") Executor retrievalExecutor,
            RetrievalObserver observer,
            RetrievalProperties properties
    ) {
        this(
                strategies,
                retrievalExecutor,
                observer,
                properties,
                PublishedLifecycleEligibility.allowAll()
        );
    }

    @Autowired
    public ParallelRetrievalExecutor(
            List<RetrievalStrategy> strategies,
            @Qualifier("retrievalExecutor") Executor retrievalExecutor,
            RetrievalObserver observer,
            RetrievalProperties properties,
            PublishedLifecycleEligibility lifecycleEligibility
    ) {
        this.strategies = new EnumMap<>(RetrievalType.class);
        strategies.forEach(strategy -> this.strategies.put(strategy.type(), strategy));
        this.retrievalExecutor = retrievalExecutor;
        this.observer = observer;
        this.properties = properties;
        this.lifecycleEligibility = lifecycleEligibility;
    }

    public List<RetrievalHit> execute(
            RetrievalPlan plan,
            Set<Long> accessLevels
    ) {
        return executeDetailed(plan, accessLevels).hits();
    }

    public RetrievalExecutionResult executeDetailed(
            RetrievalPlan plan,
            Set<Long> accessLevels
    ) {
        Set<Long> scope = normalizeAccessLevels(accessLevels);
        validateAcyclic(plan);
        Instant deadline = Instant.now().plus(properties.requestTimeout());
        Map<String, StepExecution> executions = new HashMap<>();
        for (RetrievalStep step : plan.steps()) {
            schedule(step, plan, executions, scope, deadline);
        }

        LinkedHashMap<String, RetrievalStepOutcome> outcomes =
                new LinkedHashMap<>();
        for (RetrievalStep step : plan.steps()) {
            StepExecution execution = executions.get(step.id());
            CompletableFuture<RetrievalStepOutcome> future = execution.outcome();
            long remaining =
                    Duration.between(Instant.now(), deadline).toMillis();

            if (remaining <= 0) {
                if (future.isDone()) {
                    outcomes.put(
                            step.id(),
                            completedOutcome(step, future)
                    );
                } else {
                    execution.cancel("REQUEST_DEADLINE");
                    outcomes.put(
                            step.id(),
                            timeout(step, "REQUEST_DEADLINE")
                    );
                }
                continue;
            }

            try {
                outcomes.put(
                        step.id(),
                        future.get(remaining, TimeUnit.MILLISECONDS)
                );
            } catch (java.util.concurrent.TimeoutException exception) {
                if (future.isDone()) {
                    outcomes.put(
                            step.id(),
                            completedOutcome(step, future)
                    );
                } else {
                    execution.cancel("REQUEST_DEADLINE");
                    outcomes.put(
                            step.id(),
                            timeout(step, "REQUEST_DEADLINE")
                    );
                }
            } catch (InterruptedException exception) {
                execution.cancel("INTERRUPTED");
                Thread.currentThread().interrupt();
                outcomes.put(
                        step.id(),
                        failed(
                                step,
                                RetrievalOutcomeStatus.FAILED,
                                "INTERRUPTED"
                        )
                );
            } catch (java.util.concurrent.ExecutionException exception) {
                outcomes.put(
                        step.id(),
                        outcomeFromFailure(
                                step,
                                exception.getCause()
                        )
                );
            }
        }

        List<RetrievalHit> hits = outcomes.values().stream()
                .flatMap(outcome -> outcome.hits().stream())
                .toList();
        boolean degraded = outcomes.values().stream()
                .anyMatch(outcome -> !outcome.successful());
        boolean criticalFailure = criticalFailure(plan, outcomes);
        return new RetrievalExecutionResult(hits, outcomes, degraded, criticalFailure);
    }

    private StepExecution schedule(
            RetrievalStep step,
            RetrievalPlan plan,
            Map<String, StepExecution> executions,
            Set<Long> accessLevels,
            Instant deadline
    ) {
        StepExecution existing = executions.get(step.id());
        if (existing != null) {
            return existing;
        }

        StepExecution execution = new StepExecution(
                properties.strategyTimeout(),
                throwable -> outcomeFromFailure(step, throwable)
        );
        executions.put(step.id(), execution);

        List<StepExecution> dependencies = step.dependsOn().stream()
                .map(id -> findStep(plan, id))
                .map(dependency ->
                        schedule(
                                dependency,
                                plan,
                                executions,
                                accessLevels,
                                deadline
                        )
                )
                .toList();

        CompletableFuture<Void> ready = CompletableFuture.allOf(
                dependencies.stream()
                        .map(StepExecution::outcome)
                        .toArray(CompletableFuture[]::new)
        );
        ready.whenComplete((ignored, dependencyFailure) -> {
            if (dependencyFailure != null) {
                execution.complete(
                        outcomeFromFailure(step, dependencyFailure)
                );
                return;
            }
            execution.start(
                    retrievalExecutor,
                    deadline,
                    () -> executeStep(
                            step,
                            dependencies.stream()
                                    .map(StepExecution::outcome)
                                    .toList(),
                            accessLevels
                    )
            );
        });
        return execution;
    }

    private RetrievalStepOutcome executeStep(
            RetrievalStep step,
            List<CompletableFuture<RetrievalStepOutcome>> dependencyFutures,
            Set<Long> accessLevels
    ) {
        List<RetrievalStepOutcome> dependencies = dependencyFutures.stream()
                .map(CompletableFuture::join)
                .toList();

        RetrievalStepOutcome dependencyDecision = dependencyDecision(step, dependencies);
        if (dependencyDecision != null) {
            return dependencyDecision;
        }

        RetrievalStrategy strategy = strategies.get(step.type());
        if (strategy == null) {
            return failed(step, RetrievalOutcomeStatus.FAILED, "STRATEGY_MISSING");
        }

        List<RetrievalHit> dependencyHits = dependencies.stream()
                .flatMap(outcome -> outcome.hits().stream())
                .toList();

        Instant started = Instant.now();
        try {
            List<RetrievalHit> retrieved = strategy.retrieve(
                            step.queryChunk(),
                            new RetrievalContext(
                                    List.copyOf(dependencyHits),
                                    accessLevels
                            )
                    ).stream()
                    .map(hit -> withQueryChunk(hit, step.queryChunk().id()))
                    .toList();
            List<RetrievalHit> hits = lifecycleEligibility.filter(
                    retrieved,
                    accessLevels
            );
            observer.success(
                    step.type(),
                    Duration.between(started, Instant.now()),
                    hits.size()
            );
            return new RetrievalStepOutcome(
                    step.id(),
                    step.type(),
                    hits.isEmpty()
                            ? RetrievalOutcomeStatus.EMPTY
                            : RetrievalOutcomeStatus.SUCCESS,
                    hits,
                    null
            );
        } catch (RuntimeException exception) {
            observer.failure(
                    step.type(),
                    Duration.between(started, Instant.now()),
                    exception
            );
            throw exception;
        }
    }

    private RetrievalStepOutcome dependencyDecision(
            RetrievalStep step,
            List<RetrievalStepOutcome> dependencies
    ) {
        if (dependencies.isEmpty()) {
            return null;
        }

        if (step.type() == RetrievalType.VECTOR
                || step.type() == RetrievalType.LEXICAL
                || step.type() == RetrievalType.CONCEPT) {
            RetrievalStepOutcome identifier = dependencies.stream()
                    .filter(value -> value.type() == RetrievalType.IDENTIFIER)
                    .findFirst()
                    .orElse(null);
            if (identifier != null) {
                if (identifier.status() == RetrievalOutcomeStatus.EMPTY) {
                    return new RetrievalStepOutcome(
                            step.id(),
                            step.type(),
                            RetrievalOutcomeStatus.SKIPPED_DEPENDENCY,
                            List.of(),
                            "IDENTIFIER_SCOPE_EMPTY"
                    );
                }
                if (identifier.status() != RetrievalOutcomeStatus.SUCCESS) {
                    return new RetrievalStepOutcome(
                            step.id(),
                            step.type(),
                            RetrievalOutcomeStatus.SKIPPED_DEPENDENCY,
                            List.of(),
                            "IDENTIFIER_SCOPE_UNAVAILABLE"
                    );
                }
            }
        }

        if (step.type() == RetrievalType.REFERENCE) {
            boolean anyHits = dependencies.stream()
                    .anyMatch(value -> value.status() == RetrievalOutcomeStatus.SUCCESS
                            && !value.hits().isEmpty());
            if (!anyHits) {
                return new RetrievalStepOutcome(
                        step.id(),
                        step.type(),
                        RetrievalOutcomeStatus.EMPTY,
                        List.of(),
                        null
                );
            }
        }
        return null;
    }

    private RetrievalStepOutcome outcomeFromFailure(
            RetrievalStep step,
            Throwable throwable
    ) {
        Throwable root = unwrap(throwable);
        if (root instanceof RetrievalStepTimeoutException timeout) {
            return timeout(step, timeout.category());
        }
        if (root instanceof TimeoutException) {
            return timeout(step, "STRATEGY_TIMEOUT");
        }
        if (isResourceTimeout(root)) {
            return timeout(step, "RESOURCE_TIMEOUT");
        }
        if (root instanceof RejectedExecutionException) {
            return failed(
                    step,
                    RetrievalOutcomeStatus.REJECTED,
                    "EXECUTOR_REJECTED"
            );
        }
        if (root instanceof java.util.concurrent.CancellationException) {
            return timeout(step, "CANCELLED");
        }
        return failed(
                step,
                RetrievalOutcomeStatus.FAILED,
                root == null ? "UNKNOWN" : root.getClass().getSimpleName()
        );
    }

    private boolean isResourceTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.springframework.dao.QueryTimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private RetrievalStepOutcome completedOutcome(
            RetrievalStep step,
            CompletableFuture<RetrievalStepOutcome> future
    ) {
        try {
            return future.join();
        } catch (java.util.concurrent.CancellationException exception) {
            return timeout(step, "CANCELLED");
        } catch (CompletionException exception) {
            return outcomeFromFailure(step, exception.getCause());
        }
    }

    private RetrievalStepOutcome timeout(
            RetrievalStep step,
            String category
    ) {
        return failed(step, RetrievalOutcomeStatus.TIMED_OUT, category);
    }

    private RetrievalStepOutcome failed(
            RetrievalStep step,
            RetrievalOutcomeStatus status,
            String category
    ) {
        observer.outcome(step.type(), status, category);
        return new RetrievalStepOutcome(
                step.id(),
                step.type(),
                status,
                List.of(),
                category
        );
    }

    private boolean criticalFailure(
            RetrievalPlan plan,
            Map<String, RetrievalStepOutcome> outcomes
    ) {
        Map<String, List<RetrievalStep>> byChunk = plan.steps().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        step -> step.queryChunk().id()
                ));
        for (List<RetrievalStep> steps : byChunk.values()) {
            RetrievalStep identifierStep = steps.stream()
                    .filter(step -> step.type() == RetrievalType.IDENTIFIER)
                    .findFirst()
                    .orElse(null);
            if (identifierStep != null) {
                RetrievalStepOutcome identifier = outcomes.get(identifierStep.id());
                if (identifier != null
                        && isInfrastructureFailure(identifier.status())) {
                    return true;
                }
                continue;
            }

            RetrievalStepOutcome vector = outcome(
                    steps,
                    outcomes,
                    RetrievalType.VECTOR
            );
            RetrievalStepOutcome lexical = outcome(
                    steps,
                    outcomes,
                    RetrievalType.LEXICAL
            );
            if (vector != null
                    && lexical != null
                    && isInfrastructureFailure(vector.status())
                    && isInfrastructureFailure(lexical.status())) {
                return true;
            }
        }
        return false;
    }

    private RetrievalStepOutcome outcome(
            List<RetrievalStep> steps,
            Map<String, RetrievalStepOutcome> outcomes,
            RetrievalType type
    ) {
        return steps.stream()
                .filter(step -> step.type() == type)
                .findFirst()
                .map(step -> outcomes.get(step.id()))
                .orElse(null);
    }

    private boolean isInfrastructureFailure(RetrievalOutcomeStatus status) {
        return status == RetrievalOutcomeStatus.FAILED
                || status == RetrievalOutcomeStatus.TIMED_OUT
                || status == RetrievalOutcomeStatus.REJECTED;
    }

    private RetrievalHit withQueryChunk(RetrievalHit hit, String queryChunkId) {
        Map<String, Object> metadata = new HashMap<>(hit.metadata());
        metadata.put("queryChunkId", queryChunkId);
        return new RetrievalHit(
                hit.type(),
                hit.accessLevel(),
                hit.documentId(),
                hit.generation(),
                hit.chunkId(),
                hit.text(),
                metadata,
                hit.evidence(),
                hit.fusedScore()
        );
    }

    private Set<Long> normalizeAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        java.util.TreeSet<Long> normalized = new java.util.TreeSet<>();
        for (Long value : accessLevels) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(
                        "accessLevels must contain only positive values"
                );
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private void validateAcyclic(RetrievalPlan plan) {
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (RetrievalStep step : plan.steps()) {
            visit(step, plan, visited, visiting);
        }
    }

    private void visit(
            RetrievalStep step,
            RetrievalPlan plan,
            Set<String> visited,
            Set<String> visiting
    ) {
        if (visited.contains(step.id())) {
            return;
        }
        if (!visiting.add(step.id())) {
            throw new IllegalArgumentException(
                    "Retrieval plan contains a dependency cycle at step: "
                            + step.id()
            );
        }
        for (String dependencyId : step.dependsOn()) {
            visit(findStep(plan, dependencyId), plan, visited, visiting);
        }
        visiting.remove(step.id());
        visited.add(step.id());
    }

    private RetrievalStep findStep(RetrievalPlan plan, String id) {
        return plan.steps().stream()
                .filter(step -> step.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown retrieval dependency: " + id
                ));
    }

    private static final class StepExecution {
        private final CompletableFuture<RetrievalStepOutcome> raw =
                new CompletableFuture<>();
        private final CompletableFuture<RetrievalStepOutcome> outcome;
        private final Duration timeout;
        private final AtomicReference<FutureTask<Void>> task =
                new AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private StepExecution(
                Duration timeout,
                Function<Throwable, RetrievalStepOutcome> failureMapper
        ) {
            this.timeout = timeout;
            this.outcome = raw.exceptionally(failureMapper);
        }

        private void start(
                Executor executor,
                Instant requestDeadline,
                Supplier<RetrievalStepOutcome> work
        ) {
            if (cancelled.get() || raw.isDone()) {
                return;
            }
            if (!hasFullBudget(requestDeadline)) {
                completeTimeout("REQUEST_DEADLINE_BUDGET_EXHAUSTED");
                return;
            }

            FutureTask<Void> futureTask = new FutureTask<>(() -> {
                if (cancelled.get() || raw.isDone()) {
                    return null;
                }
                if (!hasFullBudget(requestDeadline)) {
                    completeTimeout("REQUEST_DEADLINE_BUDGET_EXHAUSTED");
                    return null;
                }

                raw.orTimeout(
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS
                ).whenComplete((value, failure) -> {
                    Throwable root = failure;
                    while (root instanceof CompletionException
                            && root.getCause() != null) {
                        root = root.getCause();
                    }
                    if (root instanceof TimeoutException) {
                        cancelTaskOnly();
                    }
                });

                try {
                    raw.complete(work.get());
                } catch (Throwable throwable) {
                    raw.completeExceptionally(throwable);
                }
                return null;
            });
            if (!task.compareAndSet(null, futureTask)) {
                return;
            }
            if (cancelled.get() || raw.isDone()) {
                futureTask.cancel(true);
                return;
            }

            try {
                executor.execute(futureTask);
            } catch (RejectedExecutionException exception) {
                raw.completeExceptionally(exception);
            }
        }

        private boolean hasFullBudget(Instant requestDeadline) {
            Duration remaining = Duration.between(
                    Instant.now(),
                    requestDeadline
            );
            return !remaining.isZero()
                    && !remaining.isNegative()
                    && remaining.compareTo(timeout) >= 0;
        }

        private void complete(RetrievalStepOutcome value) {
            raw.complete(value);
        }

        private CompletableFuture<RetrievalStepOutcome> outcome() {
            return outcome;
        }

        private void cancel(String category) {
            cancelled.set(true);
            raw.completeExceptionally(
                    new RetrievalStepTimeoutException(category)
            );
            cancelTaskOnly();
        }

        private void completeTimeout(String category) {
            cancelled.set(true);
            raw.completeExceptionally(
                    new RetrievalStepTimeoutException(category)
            );
            cancelTaskOnly();
        }

        private void cancelTaskOnly() {
            FutureTask<Void> running = task.get();
            if (running != null) {
                running.cancel(true);
            }
        }
    }

    private static final class RetrievalStepTimeoutException
            extends RuntimeException {
        private final String category;

        private RetrievalStepTimeoutException(String category) {
            super(category);
            this.category = category;
        }

        private String category() {
            return category;
        }
    }
}
