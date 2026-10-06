package kz.alimbetov.akmai.rag.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphCompetitiveAdmission;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphOnlineExpansion;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphShadowExpansion;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphUtilityRecorder;
import kz.alimbetov.akmai.knowledge.graph.AssociationLearningRecorder;
import kz.alimbetov.akmai.rag.api.RagResponse;
import kz.alimbetov.akmai.rag.grounding.SemanticGroundingVerifier;
import kz.alimbetov.akmai.rag.learning.RagLearningEvent;
import kz.alimbetov.akmai.rag.learning.RagLearningRecorder;
import kz.alimbetov.akmai.rag.performance.RagPipelineObserver;
import kz.alimbetov.akmai.rag.performance.RagPipelineStage;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.retrieval.AnswerGroundingVerifier;
import kz.alimbetov.akmai.rag.retrieval.ContextAssembler;
import kz.alimbetov.akmai.rag.retrieval.ContextBudget;
import kz.alimbetov.akmai.rag.retrieval.ContextDiversityFilter;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.EvidenceQualityAssessor;
import kz.alimbetov.akmai.rag.retrieval.KnowledgeExpansion;
import kz.alimbetov.akmai.rag.retrieval.MeasuredRetrievalCoordinator;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.ParentContextExpansion;
import kz.alimbetov.akmai.rag.retrieval.PublishedContextRevalidator;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalAttributionStage;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.TemporalAuthorityFilter;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class RagQuestionService {

    private final QueryChunker queryChunker;
    private final RagFallbackMessages fallbackMessages;
    private final RetrievalPlanner retrievalPlanner;
    private final ParallelRetrievalExecutor retrievalExecutor;
    private final ResultFusion resultFusion;
    private final Reranker reranker;
    private final KnowledgeExpansion knowledgeExpansion;
    private final ContextBudget contextBudget;
    private final TemporalAuthorityFilter temporalAuthorityFilter;
    private final PublishedContextRevalidator contextRevalidator;
    private final ContextAssembler contextAssembler;
    private final CitationValidator citationValidator;
    private final AnswerGroundingVerifier answerGroundingVerifier;
    private final AnswerGenerationService answerGenerationService;
    private final AssociationLearningRecorder associationLearningRecorder;
    private final AdaptiveGraphShadowExpansion adaptiveGraphShadowExpansion;
    private final AdaptiveGraphOnlineExpansion adaptiveGraphOnlineExpansion;
    private final AdaptiveGraphCompetitiveAdmission adaptiveGraphCompetitiveAdmission;
    private final AdaptiveGraphUtilityRecorder adaptiveGraphUtilityRecorder;
    private final MeasuredRetrievalCoordinator measuredRetrievalCoordinator;
    private ParentContextExpansion parentContextExpansion;
    private ContextDiversityFilter contextDiversityFilter;
    private EvidenceQualityAssessor evidenceQualityAssessor;
    private RagLearningRecorder ragLearningRecorder;
    private SemanticGroundingVerifier semanticGroundingVerifier;
    private RagPipelineObserver ragPipelineObserver;

    public RagQuestionService(
            QueryChunker queryChunker,
            RagFallbackMessages fallbackMessages,
            RetrievalPlanner retrievalPlanner,
            ParallelRetrievalExecutor retrievalExecutor,
            ResultFusion resultFusion,
            Reranker reranker,
            KnowledgeExpansion knowledgeExpansion,
            ContextBudget contextBudget,
            TemporalAuthorityFilter temporalAuthorityFilter,
            PublishedContextRevalidator contextRevalidator,
            ContextAssembler contextAssembler,
            CitationValidator citationValidator,
            AnswerGroundingVerifier answerGroundingVerifier,
            AnswerGenerationService answerGenerationService,
            AssociationLearningRecorder associationLearningRecorder,
            AdaptiveGraphShadowExpansion adaptiveGraphShadowExpansion,
            AdaptiveGraphOnlineExpansion adaptiveGraphOnlineExpansion,
            AdaptiveGraphCompetitiveAdmission adaptiveGraphCompetitiveAdmission,
            AdaptiveGraphUtilityRecorder adaptiveGraphUtilityRecorder
    ) {
        this(
                queryChunker,
                fallbackMessages,
                retrievalPlanner,
                retrievalExecutor,
                resultFusion,
                reranker,
                knowledgeExpansion,
                contextBudget,
                temporalAuthorityFilter,
                contextRevalidator,
                contextAssembler,
                citationValidator,
                answerGroundingVerifier,
                answerGenerationService,
                associationLearningRecorder,
                adaptiveGraphShadowExpansion,
                adaptiveGraphOnlineExpansion,
                adaptiveGraphCompetitiveAdmission,
                adaptiveGraphUtilityRecorder,
                null
        );
    }

    @Autowired
    public RagQuestionService(
            QueryChunker queryChunker,
            RagFallbackMessages fallbackMessages,
            RetrievalPlanner retrievalPlanner,
            ParallelRetrievalExecutor retrievalExecutor,
            ResultFusion resultFusion,
            Reranker reranker,
            KnowledgeExpansion knowledgeExpansion,
            ContextBudget contextBudget,
            TemporalAuthorityFilter temporalAuthorityFilter,
            PublishedContextRevalidator contextRevalidator,
            ContextAssembler contextAssembler,
            CitationValidator citationValidator,
            AnswerGroundingVerifier answerGroundingVerifier,
            AnswerGenerationService answerGenerationService,
            AssociationLearningRecorder associationLearningRecorder,
            AdaptiveGraphShadowExpansion adaptiveGraphShadowExpansion,
            AdaptiveGraphOnlineExpansion adaptiveGraphOnlineExpansion,
            AdaptiveGraphCompetitiveAdmission adaptiveGraphCompetitiveAdmission,
            AdaptiveGraphUtilityRecorder adaptiveGraphUtilityRecorder,
            MeasuredRetrievalCoordinator measuredRetrievalCoordinator
    ) {
        this.queryChunker = queryChunker;
        this.fallbackMessages = fallbackMessages;
        this.retrievalPlanner = retrievalPlanner;
        this.retrievalExecutor = retrievalExecutor;
        this.resultFusion = resultFusion;
        this.reranker = reranker;
        this.knowledgeExpansion = knowledgeExpansion;
        this.contextBudget = contextBudget;
        this.temporalAuthorityFilter = temporalAuthorityFilter;
        this.contextRevalidator = contextRevalidator;
        this.contextAssembler = contextAssembler;
        this.citationValidator = citationValidator;
        this.answerGroundingVerifier = answerGroundingVerifier;
        this.answerGenerationService = answerGenerationService;
        this.associationLearningRecorder = associationLearningRecorder;
        this.adaptiveGraphShadowExpansion = adaptiveGraphShadowExpansion;
        this.adaptiveGraphOnlineExpansion = adaptiveGraphOnlineExpansion;
        this.adaptiveGraphCompetitiveAdmission = adaptiveGraphCompetitiveAdmission;
        this.adaptiveGraphUtilityRecorder = adaptiveGraphUtilityRecorder;
        this.measuredRetrievalCoordinator = measuredRetrievalCoordinator;
    }

    @Autowired(required = false)
    void setParentContextExpansion(ParentContextExpansion parentContextExpansion) {
        this.parentContextExpansion = parentContextExpansion;
    }

    @Autowired(required = false)
    void setContextDiversityFilter(ContextDiversityFilter contextDiversityFilter) {
        this.contextDiversityFilter = contextDiversityFilter;
    }

    @Autowired(required = false)
    void setEvidenceQualityAssessor(EvidenceQualityAssessor evidenceQualityAssessor) {
        this.evidenceQualityAssessor = evidenceQualityAssessor;
    }

    @Autowired(required = false)
    void setRagLearningRecorder(RagLearningRecorder ragLearningRecorder) {
        this.ragLearningRecorder = ragLearningRecorder;
    }

    @Autowired(required = false)
    void setSemanticGroundingVerifier(
            SemanticGroundingVerifier semanticGroundingVerifier
    ) {
        this.semanticGroundingVerifier = semanticGroundingVerifier;
    }

    @Autowired(required = false)
    void setRagPipelineObserver(RagPipelineObserver ragPipelineObserver) {
        this.ragPipelineObserver = ragPipelineObserver;
    }

    public RagResponse ask(String question, Set<Long> accessLevels) {
        long totalStartedNanos = System.nanoTime();
        try {
            return askInternal(question, accessLevels, totalStartedNanos);
        } finally {
            recordPerformance(RagPipelineStage.TOTAL, totalStartedNanos);
        }
    }

    private RagResponse askInternal(
            String question,
            Set<Long> accessLevels,
            long startedNanos
    ) {
        String requestId = UUID.randomUUID().toString();
        if (accessLevels == null || accessLevels.isEmpty()) {
            return insufficientInformation(question, requestId);
        }

        long stageStarted = System.nanoTime();
        List<QueryChunk> queryChunks = queryChunker.chunk(question);
        recordPerformance(RagPipelineStage.QUERY_ANALYSIS, stageStarted);

        stageStarted = System.nanoTime();
        RetrievalPlan plan = retrievalPlanner.plan(queryChunks);
        if (measuredRetrievalCoordinator != null) {
            measuredRetrievalCoordinator.observePlan(queryChunks, plan);
        }
        recordPerformance(RagPipelineStage.PLANNING, stageStarted);

        stageStarted = System.nanoTime();
        RetrievalExecutionResult execution = retrievalExecutor.executeDetailed(
                plan,
                accessLevels
        );
        recordPerformance(RagPipelineStage.RETRIEVAL, stageStarted);
        recordStage(RetrievalAttributionStage.PRODUCED, execution.hits());

        if (execution.criticalFailure()) {
            recordLearning(
                    requestId,
                    question,
                    queryChunks,
                    accessLevels,
                    execution,
                    List.of(),
                    null,
                    RagLearningEvent.AnswerStatus.UNAVAILABLE,
                    RagLearningEvent.GroundingStatus.NOT_EVALUATED,
                    startedNanos
            );
            throw new RetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable"
            );
        }

        List<RetrievalHit> finalContext;
        try {
            stageStarted = System.nanoTime();
            List<RetrievalHit> fused = resultFusion.fuse(execution.hits(), accessLevels);
            recordPerformance(RagPipelineStage.FUSION, stageStarted);
            recordStage(RetrievalAttributionStage.FUSED, fused);

            stageStarted = System.nanoTime();
            List<RetrievalHit> ranked = reranker.rerank(fused, question);
            recordPerformance(RagPipelineStage.RERANK, stageStarted);
            recordStage(RetrievalAttributionStage.RERANKED, ranked);

            stageStarted = System.nanoTime();
            List<RetrievalHit> expanded = knowledgeExpansion.expand(ranked, accessLevels);
            AdaptiveGraphShadowExpansion.ShadowExpansionReport graphReport =
                    adaptiveGraphShadowExpansion.observe(
                            ranked,
                            expanded,
                            accessLevels
                    );
            List<RetrievalHit> graphExpanded = adaptiveGraphOnlineExpansion.expand(
                    expanded,
                    graphReport,
                    accessLevels
            );
            List<RetrievalHit> competitive = adaptiveGraphCompetitiveAdmission.admit(
                    graphExpanded
            );
            recordPerformance(RagPipelineStage.EXPANSION, stageStarted);

            stageStarted = System.nanoTime();
            List<RetrievalHit> authorityEligible = temporalAuthorityFilter.filter(
                    competitive
            );
            List<RetrievalHit> parentExpanded = parentContextExpansion == null
                    ? authorityEligible
                    : parentContextExpansion.expand(authorityEligible, accessLevels);
            List<RetrievalHit> diversified = contextDiversityFilter == null
                    ? parentExpanded
                    : contextDiversityFilter.apply(parentExpanded);
            List<RetrievalHit> bounded = contextBudget.apply(diversified, question);
            finalContext = contextRevalidator.revalidate(bounded, accessLevels);
            recordPerformance(RagPipelineStage.CONTEXT_SELECTION, stageStarted);
            recordStage(RetrievalAttributionStage.SELECTED, finalContext);
        } catch (DataAccessException | TransactionException exception) {
            recordLearning(
                    requestId,
                    question,
                    queryChunks,
                    accessLevels,
                    execution,
                    List.of(),
                    null,
                    RagLearningEvent.AnswerStatus.UNAVAILABLE,
                    RagLearningEvent.GroundingStatus.NOT_EVALUATED,
                    startedNanos
            );
            throw new RetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable",
                    exception
            );
        }

        if (finalContext.isEmpty()) {
            recordLearning(
                    requestId,
                    question,
                    queryChunks,
                    accessLevels,
                    execution,
                    finalContext,
                    null,
                    RagLearningEvent.AnswerStatus.INSUFFICIENT,
                    RagLearningEvent.GroundingStatus.NOT_EVALUATED,
                    startedNanos
            );
            return insufficientInformation(question, requestId);
        }
        if (evidenceQualityAssessor != null) {
            evidenceQualityAssessor.observe(finalContext);
        }

        String context = contextAssembler.assemble(finalContext);
        stageStarted = System.nanoTime();
        String answer = answerGenerationService.generate(question, context);
        recordPerformance(RagPipelineStage.GENERATION, stageStarted);

        stageStarted = System.nanoTime();
        CitationValidator.CitationValidation validation = citationValidator.validate(
                answer,
                finalContext
        );
        recordPerformance(RagPipelineStage.CITATION, stageStarted);
        if (measuredRetrievalCoordinator != null) {
            measuredRetrievalCoordinator.recordCitations(finalContext, validation);
        }

        if (validation.answer().isBlank()
                || validation.citedSources().isEmpty()) {
            adaptiveGraphUtilityRecorder.record(finalContext, validation, false);
            recordLearning(
                    requestId,
                    question,
                    queryChunks,
                    accessLevels,
                    execution,
                    finalContext,
                    validation,
                    RagLearningEvent.AnswerStatus.INSUFFICIENT,
                    RagLearningEvent.GroundingStatus.DETERMINISTIC_REJECTED,
                    startedNanos
            );
            return insufficientInformation(question, requestId);
        }

        stageStarted = System.nanoTime();
        AnswerGroundingVerifier.GroundingValidation grounding =
                answerGroundingVerifier.verify(
                        validation.answer(),
                        finalContext,
                        groundingLanguage(queryChunks)
                );
        recordPerformance(RagPipelineStage.DETERMINISTIC_GROUNDING, stageStarted);
        if (!grounding.grounded()) {
            adaptiveGraphUtilityRecorder.record(finalContext, validation, false);
            recordLearning(
                    requestId,
                    question,
                    queryChunks,
                    accessLevels,
                    execution,
                    finalContext,
                    validation,
                    RagLearningEvent.AnswerStatus.UNGROUNDED,
                    RagLearningEvent.GroundingStatus.DETERMINISTIC_REJECTED,
                    startedNanos
            );
            return insufficientInformation(question, requestId);
        }

        stageStarted = System.nanoTime();
        SemanticGroundingVerifier.Verification semantic = semanticGroundingVerifier == null
                ? null
                : semanticGroundingVerifier.verify(grounding, finalContext);
        recordPerformance(RagPipelineStage.SEMANTIC_GROUNDING, stageStarted);
        if (semantic != null && !semantic.accepted()) {
            adaptiveGraphUtilityRecorder.record(finalContext, validation, false);
            RagLearningEvent.GroundingStatus status =
                    semantic.status() == SemanticGroundingVerifier.Status.CONTRADICTED
                            ? RagLearningEvent.GroundingStatus.CONTRADICTED
                            : RagLearningEvent.GroundingStatus.INSUFFICIENT;
            recordLearning(
                    requestId,
                    question,
                    queryChunks,
                    accessLevels,
                    execution,
                    finalContext,
                    validation,
                    RagLearningEvent.AnswerStatus.UNGROUNDED,
                    status,
                    startedNanos
            );
            return insufficientInformation(question, requestId);
        }

        adaptiveGraphUtilityRecorder.record(finalContext, validation, true);
        if (measuredRetrievalCoordinator != null) {
            measuredRetrievalCoordinator.recordGrounded(finalContext, validation);
        }

        associationLearningRecorder.record(
                queryChunks,
                accessLevels,
                finalContext,
                validation
        );

        recordLearning(
                requestId,
                question,
                queryChunks,
                accessLevels,
                execution,
                finalContext,
                validation,
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                startedNanos
        );

        List<RagResponse.Source> sources = validation.citedSources().stream()
                .map(source -> new RagResponse.Source(
                        source.number(),
                        source.documentId(),
                        source.chunkId(),
                        source.source(),
                        source.language(),
                        source.sectionPath(),
                        source.page()
                ))
                .toList();

        return new RagResponse(requestId, validation.answer(), sources);
    }

    private void recordLearning(
            String requestId,
            String question,
            List<QueryChunk> queryChunks,
            Set<Long> accessLevels,
            RetrievalExecutionResult execution,
            List<RetrievalHit> finalContext,
            CitationValidator.CitationValidation validation,
            RagLearningEvent.AnswerStatus answerStatus,
            RagLearningEvent.GroundingStatus groundingStatus,
            long startedNanos
    ) {
        if (ragLearningRecorder == null) {
            return;
        }
        ragLearningRecorder.record(
                requestId,
                question,
                queryChunks,
                accessLevels,
                execution,
                finalContext,
                validation,
                answerStatus,
                groundingStatus,
                elapsedMillis(startedNanos)
        );
    }

    private void recordPerformance(RagPipelineStage stage, long startedNanos) {
        if (ragPipelineObserver == null) {
            return;
        }
        ragPipelineObserver.record(
                stage,
                Math.max(0, System.nanoTime() - startedNanos)
        );
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(
                0,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        );
    }

    private String groundingLanguage(List<QueryChunk> queryChunks) {
        if (queryChunks == null || queryChunks.isEmpty()) {
            return "unknown";
        }
        String selected = null;
        for (QueryChunk chunk : queryChunks) {
            if (chunk == null || chunk.language() == null
                    || chunk.language().isBlank()) {
                return "unknown";
            }
            String language = baseLanguage(chunk.language());
            if (selected == null) {
                selected = language;
            } else if (!selected.equals(language)) {
                return "unknown";
            }
        }
        return selected == null ? "unknown" : selected;
    }

    private String baseLanguage(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        int hyphen = normalized.indexOf('-');
        int underscore = normalized.indexOf('_');
        int delimiter;
        if (hyphen < 0) {
            delimiter = underscore;
        } else if (underscore < 0) {
            delimiter = hyphen;
        } else {
            delimiter = Math.min(hyphen, underscore);
        }
        return delimiter > 0 ? normalized.substring(0, delimiter) : normalized;
    }

    private void recordStage(
            RetrievalAttributionStage stage,
            List<RetrievalHit> hits
    ) {
        if (measuredRetrievalCoordinator != null) {
            measuredRetrievalCoordinator.recordStage(stage, hits);
        }
    }

    private RagResponse insufficientInformation(
            String question,
            String requestId
    ) {
        return new RagResponse(
                requestId,
                fallbackMessages.insufficientInformation(question),
                List.of()
        );
    }
}
