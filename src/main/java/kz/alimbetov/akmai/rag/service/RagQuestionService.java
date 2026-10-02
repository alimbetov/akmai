package kz.alimbetov.akmai.rag.service;

import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.rag.api.RagResponse;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.retrieval.ContextAssembler;
import kz.alimbetov.akmai.rag.retrieval.ContextBudget;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.KnowledgeExpansion;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.springframework.stereotype.Service;

@Service
public class RagQuestionService {

    private static final String INSUFFICIENT_INFORMATION =
            "В базе знаний недостаточно информации.";

    private final QueryChunker queryChunker;
    private final RetrievalPlanner retrievalPlanner;
    private final ParallelRetrievalExecutor retrievalExecutor;
    private final ResultFusion resultFusion;
    private final Reranker reranker;
    private final KnowledgeExpansion knowledgeExpansion;
    private final ContextBudget contextBudget;
    private final ContextAssembler contextAssembler;
    private final CitationValidator citationValidator;
    private final AnswerGenerationService answerGenerationService;

    public RagQuestionService(
            QueryChunker queryChunker,
            RetrievalPlanner retrievalPlanner,
            ParallelRetrievalExecutor retrievalExecutor,
            ResultFusion resultFusion,
            Reranker reranker,
            KnowledgeExpansion knowledgeExpansion,
            ContextBudget contextBudget,
            ContextAssembler contextAssembler,
            CitationValidator citationValidator,
            AnswerGenerationService answerGenerationService
    ) {
        this.queryChunker = queryChunker;
        this.retrievalPlanner = retrievalPlanner;
        this.retrievalExecutor = retrievalExecutor;
        this.resultFusion = resultFusion;
        this.reranker = reranker;
        this.knowledgeExpansion = knowledgeExpansion;
        this.contextBudget = contextBudget;
        this.contextAssembler = contextAssembler;
        this.citationValidator = citationValidator;
        this.answerGenerationService = answerGenerationService;
    }

    public RagResponse ask(String question) {
        return ask(question, Set.of());
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

        List<RetrievalHit> fused = resultFusion.fuse(execution.hits(), accessLevels);
        List<RetrievalHit> ranked = reranker.rerank(fused, question);
        List<RetrievalHit> expanded = knowledgeExpansion.expand(ranked, accessLevels);
        List<RetrievalHit> bounded = contextBudget.apply(expanded, question);

        if (bounded.isEmpty()) {
            return insufficientInformation();
        }

        String context = contextAssembler.assemble(bounded);
        String answer = answerGenerationService.generate(question, context);

        CitationValidator.CitationValidation validation =
                citationValidator.validate(answer, bounded);

        if (validation.answer().isBlank()
                || validation.citedSources().isEmpty()) {
            return insufficientInformation();
        }

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

    private RagResponse insufficientInformation() {
        return new RagResponse(INSUFFICIENT_INFORMATION, List.of());
    }
}
