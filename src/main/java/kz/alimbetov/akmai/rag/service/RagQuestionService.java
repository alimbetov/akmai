package kz.alimbetov.akmai.rag.service;

import java.util.List;
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
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class RagQuestionService {

    private final QueryChunker queryChunker;
    private final RetrievalPlanner retrievalPlanner;
    private final ParallelRetrievalExecutor retrievalExecutor;
    private final ResultFusion resultFusion;
    private final Reranker reranker;
    private final KnowledgeExpansion knowledgeExpansion;
    private final ContextBudget contextBudget;
    private final ContextAssembler contextAssembler;
    private final CitationValidator citationValidator;
    private final ChatClient chatClient;

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
            ChatClient.Builder chatClientBuilder
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
        this.chatClient = chatClientBuilder.build();
    }

    public RagResponse ask(String question) {
        List<QueryChunk> queryChunks = queryChunker.chunk(question);
        RetrievalPlan plan = retrievalPlanner.plan(queryChunks);
        List<RetrievalHit> retrieved = retrievalExecutor.execute(plan);
        List<RetrievalHit> fused = resultFusion.fuse(retrieved);
        List<RetrievalHit> ranked = reranker.rerank(fused, question);
        List<RetrievalHit> expanded = knowledgeExpansion.expand(ranked);
        List<RetrievalHit> bounded = contextBudget.apply(expanded);

        if (bounded.isEmpty()) {
            return new RagResponse("В базе знаний недостаточно информации.", List.of());
        }

        String context = contextAssembler.assemble(bounded);

        String answer = chatClient.prompt()
                .system("""
                        Ты ассистент корпоративной базы знаний.
                        Отвечай только на основании предоставленного CONTEXT.
                        Не придумывай отсутствующие факты.
                        Если информации недостаточно, прямо сообщи об этом.
                        Отвечай на языке вопроса пользователя.
                        Для медицинских и юридических данных не скрывай условия,
                        исключения, противопоказания, ограничения и ссылки.
                        Использованные источники обозначай как [SOURCE 1], [SOURCE 2] и т.д.
                        """)
                .user("""
                        QUESTION:
                        %s

                        CONTEXT:
                        %s
                        """.formatted(question, context))
                .call()
                .content();

        CitationValidator.CitationValidation validation =
                citationValidator.validate(answer, bounded);

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
}
