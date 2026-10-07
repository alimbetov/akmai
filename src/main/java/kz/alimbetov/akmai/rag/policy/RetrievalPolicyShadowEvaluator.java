package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.rag.learning.LearningPrivacyFingerprint;
import kz.alimbetov.akmai.rag.learning.LearningSourceFingerprint;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.SourceRef;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import kz.alimbetov.akmai.rag.retrieval.plan.ShadowRetrievalPlanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RetrievalPolicyShadowEvaluator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RetrievalPolicyShadowEvaluator.class);

    private final ShadowRetrievalPolicyProvider shadowPolicyProvider;
    private final AdaptiveRetrievalPlanner adaptivePlanner;
    private final RetrievalPlanner retrievalPlanner;
    private final ShadowRetrievalPlanBuilder shadowPlanBuilder;
    private final ParallelRetrievalExecutor retrievalExecutor;
    private final ResultFusion resultFusion;
    private final Reranker reranker;
    private final LearningPrivacyFingerprint queryFingerprint;
    private final LearningSourceFingerprint sourceFingerprint;
    private final RagPolicyShadowObservationRepository repository;
    private final ExecutorService executor;

    public RetrievalPolicyShadowEvaluator(
            ShadowRetrievalPolicyProvider shadowPolicyProvider,
            AdaptiveRetrievalPlanner adaptivePlanner,
            RetrievalPlanner retrievalPlanner,
            ShadowRetrievalPlanBuilder shadowPlanBuilder,
            ParallelRetrievalExecutor retrievalExecutor,
            ResultFusion resultFusion,
            Reranker reranker,
            LearningPrivacyFingerprint queryFingerprint,
            LearningSourceFingerprint sourceFingerprint,
            RagPolicyShadowObservationRepository repository,
            @Qualifier("shadowEvaluationExecutor") ExecutorService executor
    ) {
        this.shadowPolicyProvider = shadowPolicyProvider;
        this.adaptivePlanner = adaptivePlanner;
        this.retrievalPlanner = retrievalPlanner;
        this.shadowPlanBuilder = shadowPlanBuilder;
        this.retrievalExecutor = retrievalExecutor;
        this.resultFusion = resultFusion;
        this.reranker = reranker;
        this.queryFingerprint = queryFingerprint;
        this.sourceFingerprint = sourceFingerprint;
        this.repository = repository;
        this.executor = executor;
    }

    public void observeGrounded(
            String question,
            List<QueryChunk> queryChunks,
            Set<Long> accessLevels,
            List<RetrievalHit> finalContext,
            CitationValidator.CitationValidation validation
    ) {
        String policyVersion = shadowPolicyProvider.shadowVersion()
                .orElse(null);
        if (policyVersion == null
                || question == null
                || question.isBlank()
                || queryChunks == null
                || queryChunks.isEmpty()
                || accessLevels == null
                || accessLevels.isEmpty()
                || finalContext == null
                || finalContext.isEmpty()
                || validation == null
                || validation.citedSources() == null
                || validation.citedSources().isEmpty()) {
            return;
        }

        String queryKey = queryFingerprint.fingerprint(question);
        String sourceKey = sourceFingerprint.current();
        if (queryKey.isBlank() || sourceKey.isBlank()) {
            return;
        }
        Set<String> targetChunks = targetChunks(finalContext, validation);
        Set<String> targetDocuments = validation.citedSources().stream()
                .map(SourceRef::documentId)
                .filter(this::usableId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (targetChunks.isEmpty() && targetDocuments.isEmpty()) {
            return;
        }
        AdaptiveRetrievalPlanner.QueryClass queryClass =
                adaptivePlanner.classifyPrimary(queryChunks);

        try {
            executor.submit(() -> evaluate(
                    policyVersion,
                    queryKey,
                    sourceKey,
                    queryClass,
                    question,
                    List.copyOf(queryChunks),
                    Set.copyOf(accessLevels),
                    targetChunks,
                    targetDocuments
            ));
        } catch (RejectedExecutionException exception) {
            LOGGER.debug("rag_shadow event=rejected policy={}", policyVersion);
        }
    }

    private void evaluate(
            String policyVersion,
            String queryKey,
            String sourceKey,
            AdaptiveRetrievalPlanner.QueryClass queryClass,
            String question,
            List<QueryChunk> queryChunks,
            Set<Long> accessLevels,
            Set<String> targetChunks,
            Set<String> targetDocuments
    ) {
        long started = System.nanoTime();
        boolean changed = false;
        try {
            RetrievalPlan productionPlan = retrievalPlanner.plan(queryChunks);
            AdaptiveRetrievalPlanner.ShadowPlanReport report =
                    adaptivePlanner.shadow(queryChunks, productionPlan);
            RetrievalPlan shadowPlan = shadowPlanBuilder.build(
                    queryChunks,
                    report,
                    productionPlan
            );
            changed = !samePlan(productionPlan, shadowPlan);
            if (!changed) {
                save(
                        policyVersion,
                        queryKey,
                        sourceKey,
                        queryClass,
                        false,
                        RagPolicyShadowObservationRepository.Status.NO_CHANGE,
                        targetChunks.size(),
                        targetChunks.size(),
                        targetDocuments.size(),
                        targetDocuments.size(),
                        started
                );
                return;
            }

            RetrievalExecutionResult execution = retrievalExecutor.executeDetailed(
                    shadowPlan,
                    accessLevels
            );
            List<RetrievalHit> fused = resultFusion.fuse(
                    execution.hits(),
                    accessLevels
            );
            List<RetrievalHit> ranked = reranker.rerank(fused, question);
            Set<String> foundChunkIds = chunkIds(ranked);
            Set<String> foundDocumentIds = ranked.stream()
                    .map(RetrievalHit::documentId)
                    .filter(this::usableId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

            RagPolicyShadowObservationRepository.Status status =
                    execution.criticalFailure()
                            ? RagPolicyShadowObservationRepository.Status.CRITICAL_FAILURE
                            : execution.degraded()
                                    ? RagPolicyShadowObservationRepository.Status.DEGRADED
                                    : RagPolicyShadowObservationRepository.Status.SUCCESS;
            save(
                    policyVersion,
                    queryKey,
                    sourceKey,
                    queryClass,
                    true,
                    status,
                    targetChunks.size(),
                    intersectionSize(targetChunks, foundChunkIds),
                    targetDocuments.size(),
                    intersectionSize(targetDocuments, foundDocumentIds),
                    started
            );
        } catch (RuntimeException exception) {
            save(
                    policyVersion,
                    queryKey,
                    sourceKey,
                    queryClass,
                    changed,
                    RagPolicyShadowObservationRepository.Status.FAILED,
                    targetChunks.size(),
                    0,
                    targetDocuments.size(),
                    0,
                    started
            );
            LOGGER.debug(
                    "rag_shadow event=evaluation_failed policy={} errorType={}",
                    policyVersion,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private boolean samePlan(RetrievalPlan left, RetrievalPlan right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.steps().stream().map(RetrievalStep::id).toList()
                .equals(right.steps().stream().map(RetrievalStep::id).toList());
    }

    private Set<String> targetChunks(
            List<RetrievalHit> context,
            CitationValidator.CitationValidation validation
    ) {
        Set<String> ids = new HashSet<>();
        for (SourceRef source : validation.citedSources()) {
            if (usableId(source.chunkId())) {
                ids.add(source.chunkId());
            }
            int index = source.number() - 1;
            if (index >= 0 && index < context.size()) {
                String matchedChild = ChunkRole.matchedChildChunkId(
                        context.get(index).metadata()
                );
                if (usableId(matchedChild)) {
                    ids.add(matchedChild);
                }
            }
        }
        return Set.copyOf(ids);
    }

    private Set<String> chunkIds(List<RetrievalHit> hits) {
        Set<String> ids = new HashSet<>();
        for (RetrievalHit hit : hits) {
            if (hit == null) {
                continue;
            }
            if (usableId(hit.chunkId())) {
                ids.add(hit.chunkId());
            }
            String matchedChild = ChunkRole.matchedChildChunkId(hit.metadata());
            if (usableId(matchedChild)) {
                ids.add(matchedChild);
            }
        }
        return Set.copyOf(ids);
    }

    private int intersectionSize(Set<String> expected, Set<String> actual) {
        int count = 0;
        for (String value : expected) {
            if (actual.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private boolean usableId(String value) {
        return value != null
                && !value.isBlank()
                && !"unknown".equalsIgnoreCase(value);
    }

    private void save(
            String policyVersion,
            String queryKey,
            String sourceKey,
            AdaptiveRetrievalPlanner.QueryClass queryClass,
            boolean changed,
            RagPolicyShadowObservationRepository.Status status,
            int targetChunks,
            int foundChunks,
            int targetDocuments,
            int foundDocuments,
            long started
    ) {
        try {
            repository.save(new RagPolicyShadowObservationRepository.Observation(
                    policyVersion,
                    queryKey,
                    sourceKey,
                    queryClass == null ? "ANALYSIS_UNAVAILABLE" : queryClass.name(),
                    changed,
                    status,
                    targetChunks,
                    foundChunks,
                    targetDocuments,
                    foundDocuments,
                    Math.max(
                            0,
                            TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - started
                            )
                    ),
                    Instant.now()
            ));
        } catch (RuntimeException ignored) {
            // Experimental evidence cannot affect production availability.
        }
    }
}
