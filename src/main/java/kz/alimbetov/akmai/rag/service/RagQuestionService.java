package kz.alimbetov.akmai.rag.service;

import java.util.List;
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
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.KnowledgeExpansion;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.PublishedContextRevalidator;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.TemporalAuthorityFilter;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
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
    }

    public RagResponse ask(String question, Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            return insufficientInformation();
        }

        List<QueryChunk> queryChunks = queryChunker.chunk(question);
        RetrievalPlan plan = retrievalPlanner.plan(queryChunks);
        RetrievalExecutionResult execution =
                retrievalExecutor.executeDetailed(plan, accessLevels);

        if (execution.criticalFailure()) {
            throw new RetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable"
            );
        }

        List<RetrievalHit> finalContext;
        try {
            List<RetrievalHit> fused =
                    resultFusion.fuse(execution.hits(), accessLevels);
            List<RetrievalHit> ranked =
                    reranker.rerank(fused, question);
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
            List<RetrievalHit> bounded =
                    contextBudget.apply(authorityEligible, question);
            finalContext = contextRevalidator.revalidate(
                    bounded,
                    accessLevels
            );
        } catch (DataAccessException | TransactionException exception) {
            throw new RetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable",
                    exception
            );
        }

        if (finalContext.isEmpty()) {
            return insufficientInformation();
        }

        String context = contextAssembler.assemble(finalContext);
        String answer = answerGenerationService.generate(question, context);

        CitationValidator.CitationValidation validation =
                citationValidator.validate(answer, finalContext);
        adaptiveGraphUtilityRecorder.record(finalContext, validation);

        if (validation.answer().isBlank()
                || validation.citedSources().isEmpty()) {
            return insufficientInformation();
        }

        AnswerGroundingVerifier.GroundingValidation grounding =
                answerGroundingVerifier.verify(
                        validation.answer(),
                        finalContext
                );
        if (!grounding.grounded()) {
            return insufficientInformation();
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

    private RagResponse insufficientInformation(String question) {
        return new RagResponse(
                fallbackMessages.insufficientInformation(question),
                List.of()
        );
    }
}
