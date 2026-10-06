package kz.alimbetov.akmai.rag.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphCompetitiveAdmission;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphOnlineExpansion;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphShadowExpansion;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphUtilityRecorder;
import kz.alimbetov.akmai.knowledge.graph.AssociationLearningRecorder;
import kz.alimbetov.akmai.rag.api.RagResponse;
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
        this.adaptiveGraphCompetitiveAdmission =
                adaptiveGraphCompetitiveAdmission;
        this.adaptiveGraphUtilityRecorder = adaptiveGraphUtilityRecorder;
        this.measuredRetrievalCoordinator = measuredRetrievalCoordinator;
    }

    @Autowired(required = false)
    void setParentContextExpansion(
            ParentContextExpansion parentContextExpansion
    ) {
        this.parentContextExpansion = parentContextExpansion;
    }

    @Autowired(required = false)
    void setContextDiversityFilter(
            ContextDiversityFilter contextDiversityFilter
    ) {
        this.contextDiversityFilter = contextDiversityFilter;
    }

    @Autowired(required = false)
    void setEvidenceQualityAssessor(
            EvidenceQualityAssessor evidenceQualityAssessor
    ) {
        this.evidenceQualityAssessor = evidenceQualityAssessor;
    }

    public RagResponse ask(String question, Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            return insufficientInformation(question);
        }

        List<QueryChunk> queryChunks = queryChunker.chunk(question);
        RetrievalPlan plan = retrievalPlanner.plan(queryChunks);
        if (measuredRetrievalCoordinator != null) {
            measuredRetrievalCoordinator.observePlan(queryChunks, plan);
        }

        RetrievalExecutionResult execution =
                retrievalExecutor.executeDetailed(plan, accessLevels);
        recordStage(RetrievalAttributionStage.PRODUCED, execution.hits());

        if (execution.criticalFailure()) {
            throw new RetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable"
            );
        }

        List<RetrievalHit> finalContext;
        try {
            List<RetrievalHit> fused =
                    resultFusion.fuse(execution.hits(), accessLevels);
            recordStage(RetrievalAttributionStage.FUSED, fused);

            List<RetrievalHit> ranked =
                    reranker.rerank(fused, question);
            recordStage(RetrievalAttributionStage.RERANKED, ranked);

            List<RetrievalHit> expanded =
                    knowledgeExpansion.expand(ranked, accessLevels);

            AdaptiveGraphShadowExpansion.ShadowExpansionReport graphReport =
                    adaptiveGraphShadowExpansion.observe(
                            ranked,
                            expanded,
                            accessLevels
                    );
            List<RetrievalHit> graphExpanded =
                    adaptiveGraphOnlineExpansion.expand(
                            expanded,
                            graphReport,
                            accessLevels
                    );
            List<RetrievalHit> competitive =
                    adaptiveGraphCompetitiveAdmission.admit(
                            graphExpanded
                    );

            List<RetrievalHit> authorityEligible =
                    temporalAuthorityFilter.filter(competitive);
            List<RetrievalHit> parentExpanded = parentContextExpansion == null
                    ? authorityEligible
                    : parentContextExpansion.expand(
                            authorityEligible,
                            accessLevels
                    );
            List<RetrievalHit> diversified = contextDiversityFilter == null
                    ? parentExpanded
                    : contextDiversityFilter.apply(parentExpanded);
            List<RetrievalHit> bounded =
                    contextBudget.apply(diversified, question);
            finalContext = contextRevalidator.revalidate(
                    bounded,
                    accessLevels
            );
            recordStage(RetrievalAttributionStage.SELECTED, finalContext);
        } catch (DataAccessException | TransactionException exception) {
            throw new RetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable",
                    exception
            );
        }

        if (finalContext.isEmpty()) {
            return insufficientInformation(question);
        }
        if (evidenceQualityAssessor != null) {
            evidenceQualityAssessor.observe(finalContext);
        }

        String context = contextAssembler.assemble(finalContext);
        String answer = answerGenerationService.generate(question, context);

        CitationValidator.CitationValidation validation =
                citationValidator.validate(answer, finalContext);
        if (measuredRetrievalCoordinator != null) {
            measuredRetrievalCoordinator.recordCitations(
                    finalContext,
                    validation
            );
        }

        if (validation.answer().isBlank()
                || validation.citedSources().isEmpty()) {
            adaptiveGraphUtilityRecorder.record(
                    finalContext,
                    validation,
                    false
            );
            return insufficientInformation(question);
        }

        AnswerGroundingVerifier.GroundingValidation grounding =
                answerGroundingVerifier.verify(
                        validation.answer(),
                        finalContext,
                        groundingLanguage(queryChunks)
                );
        adaptiveGraphUtilityRecorder.record(
                finalContext,
                validation,
                grounding.grounded()
        );
        if (!grounding.grounded()) {
            return insufficientInformation(question);
        }
        if (measuredRetrievalCoordinator != null) {
            measuredRetrievalCoordinator.recordGrounded(
                    finalContext,
                    validation
            );
        }

        associationLearningRecorder.record(
                queryChunks,
                accessLevels,
                finalContext,
                validation
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

        return new RagResponse(validation.answer(), sources);
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

    private RagResponse insufficientInformation(String question) {
        return new RagResponse(
                fallbackMessages.insufficientInformation(question),
                List.of()
        );
    }
}
