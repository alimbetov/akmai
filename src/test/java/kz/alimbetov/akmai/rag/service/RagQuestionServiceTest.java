package kz.alimbetov.akmai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphOnlineExpansion;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphShadowExpansion;
import kz.alimbetov.akmai.knowledge.graph.AssociationLearningRecorder;
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
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.junit.jupiter.api.Test;

class RagQuestionServiceTest {

    @Test
    void uncitedModelAnswerIsRejectedAsUngrounded() {
        QueryChunker chunker = mock(QueryChunker.class);
        RetrievalPlanner planner = mock(RetrievalPlanner.class);
        ParallelRetrievalExecutor executor =
                mock(ParallelRetrievalExecutor.class);
        ResultFusion fusion = mock(ResultFusion.class);
        Reranker reranker = mock(Reranker.class);
        KnowledgeExpansion expansion = mock(KnowledgeExpansion.class);
        ContextBudget budget = mock(ContextBudget.class);
        ContextAssembler assembler = mock(ContextAssembler.class);
        AnswerGenerationService generation =
                mock(AnswerGenerationService.class);
        AssociationLearningRecorder learning =
                mock(AssociationLearningRecorder.class);
        AdaptiveGraphShadowExpansion shadowExpansion =
                mock(AdaptiveGraphShadowExpansion.class);
        AdaptiveGraphOnlineExpansion onlineExpansion =
                mock(AdaptiveGraphOnlineExpansion.class);

        QueryChunk query = new QueryChunk(
                "q", 0, "question", "question", "question", "en", List.of()
        );
        RetrievalPlan plan = new RetrievalPlan(List.of());
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc",
                "chunk",
                "grounded fact",
                Map.of(
                        "source", "source.md",
                        "language", "en",
                        "sectionPath", "section",
                        "generation", 1L
                )
        );

        when(chunker.chunk("question")).thenReturn(List.of(query));
        when(planner.plan(List.of(query))).thenReturn(plan);
        Set<Long> scope = Set.of(1L);
        when(executor.executeDetailed(plan, scope)).thenReturn(
                new RetrievalExecutionResult(
                        List.of(hit),
                        Map.of(),
                        false,
                        false
                )
        );
        when(fusion.fuse(List.of(hit), scope)).thenReturn(List.of(hit));
        when(reranker.rerank(List.of(hit), "question"))
                .thenReturn(List.of(hit));
        when(expansion.expand(List.of(hit), scope)).thenReturn(List.of(hit));
        AdaptiveGraphShadowExpansion.ShadowExpansionReport graphReport =
                new AdaptiveGraphShadowExpansion.ShadowExpansionReport(
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        List.of(),
                        false
                );
        when(shadowExpansion.observe(
                List.of(hit),
                List.of(hit),
                scope
        )).thenReturn(graphReport);
        when(onlineExpansion.expand(
                List.of(hit),
                graphReport,
                scope
        )).thenReturn(List.of(hit));
        when(budget.apply(List.of(hit), "question")).thenReturn(List.of(hit));
        when(assembler.assemble(anyList())).thenReturn("{\"sources\":[]}");
        when(generation.generate(eq("question"), eq("{\"sources\":[]}")))
                .thenReturn("Grounded-looking answer without citation.");

        RagQuestionService service = new RagQuestionService(
                chunker,
                planner,
                executor,
                fusion,
                reranker,
                expansion,
                budget,
                assembler,
                new CitationValidator(),
                generation,
                learning,
                shadowExpansion,
                onlineExpansion
        );

        var response = service.ask("question", scope);

        assertThat(response.answer())
                .isEqualTo("В базе знаний недостаточно информации.");
        assertThat(response.sources()).isEmpty();
    }
}
