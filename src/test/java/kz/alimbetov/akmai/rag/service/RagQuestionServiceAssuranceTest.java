package kz.alimbetov.akmai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphCompetitiveAdmission;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphOnlineExpansion;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphShadowExpansion;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveGraphUtilityRecorder;
import kz.alimbetov.akmai.knowledge.graph.AssociationLearningRecorder;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.retrieval.AnswerGroundingVerifier;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.ContextAssembler;
import kz.alimbetov.akmai.rag.retrieval.ContextBudget;
import kz.alimbetov.akmai.rag.retrieval.KnowledgeExpansion;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.PublishedContextRevalidator;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.TemporalAuthorityFilter;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.junit.jupiter.api.Test;

class RagQuestionServiceAssuranceTest {

    private static final Set<Long> SCOPE = Set.of(1L);

    @Test
    void acceptsGroundedNumericAnswerAndRecordsLearning() {
        Fixture fixture = fixture(
                hit(
                        "The recommended dose is 10 mg.",
                        Map.of()
                ),
                "The recommended dose is 10 mg [SOURCE 1]."
        );

        var response = fixture.service().ask("What dose?", SCOPE);

        assertThat(response.answer())
                .isEqualTo("The recommended dose is 10 mg [SOURCE 1].");
        assertThat(response.sources()).singleElement()
                .satisfies(source -> {
                    assertThat(source.number()).isEqualTo(1);
                    assertThat(source.chunkId()).isEqualTo("chunk-1");
                });
        verify(fixture.learning()).record(
                anyList(),
                eq(SCOPE),
                anyList(),
                any(CitationValidator.CitationValidation.class)
        );
        verify(fixture.utility()).record(
                anyList(),
                any(CitationValidator.CitationValidation.class),
                eq(true)
        );
    }

    @Test
    void rejectsCitedNumericHallucinationBeforeLearning() {
        Fixture fixture = fixture(
                hit(
                        "The recommended dose is 10 mg.",
                        Map.of()
                ),
                "The recommended dose is 20 mg [SOURCE 1]."
        );

        var response = fixture.service().ask("What dose?", SCOPE);

        assertThat(response.answer())
                .isEqualTo("В базе знаний недостаточно информации.");
        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(fixture.learning());
        verify(fixture.utility()).record(
                anyList(),
                any(CitationValidator.CitationValidation.class),
                eq(false)
        );
    }

    @Test
    void supersededEvidenceNeverReachesAnswerGeneration() {
        Fixture fixture = fixture(
                hit(
                        "The recommended dose is 10 mg.",
                        Map.of("documentStatus", "SUPERSEDED")
                ),
                "The recommended dose is 10 mg [SOURCE 1]."
        );

        var response = fixture.service().ask("What dose?", SCOPE);

        assertThat(response.answer())
                .isEqualTo("В базе знаний недостаточно информации.");
        verify(fixture.generation(), never()).generate(any(), any());
        verifyNoInteractions(fixture.learning());
    }

    private Fixture fixture(RetrievalHit hit, String answer) {
        QueryChunker chunker = mock(QueryChunker.class);
        RetrievalPlanner planner = mock(RetrievalPlanner.class);
        ParallelRetrievalExecutor executor =
                mock(ParallelRetrievalExecutor.class);
        ResultFusion fusion = mock(ResultFusion.class);
        Reranker reranker = mock(Reranker.class);
        KnowledgeExpansion expansion = mock(KnowledgeExpansion.class);
        ContextBudget budget = mock(ContextBudget.class);
        PublishedContextRevalidator revalidator =
                mock(PublishedContextRevalidator.class);
        ContextAssembler assembler = mock(ContextAssembler.class);
        AnswerGenerationService generation =
                mock(AnswerGenerationService.class);
        AssociationLearningRecorder learning =
                mock(AssociationLearningRecorder.class);
        AdaptiveGraphShadowExpansion shadow =
                mock(AdaptiveGraphShadowExpansion.class);
        AdaptiveGraphOnlineExpansion online =
                mock(AdaptiveGraphOnlineExpansion.class);
        AdaptiveGraphCompetitiveAdmission competition =
                mock(AdaptiveGraphCompetitiveAdmission.class);
        AdaptiveGraphUtilityRecorder utility =
                mock(AdaptiveGraphUtilityRecorder.class);

        QueryChunk query = new QueryChunk(
                "q",
                0,
                "What dose?",
                "What dose?",
                "What dose?",
                "en",
                List.of()
        );
        RetrievalPlan plan = new RetrievalPlan(List.of());
        when(chunker.chunk("What dose?")).thenReturn(List.of(query));
        when(planner.plan(List.of(query))).thenReturn(plan);
        when(executor.executeDetailed(plan, SCOPE)).thenReturn(
                new RetrievalExecutionResult(
                        List.of(hit),
                        Map.of(),
                        false,
                        false
                )
        );
        when(fusion.fuse(List.of(hit), SCOPE)).thenReturn(List.of(hit));
        when(reranker.rerank(List.of(hit), "What dose?"))
                .thenReturn(List.of(hit));
        when(expansion.expand(List.of(hit), SCOPE)).thenReturn(List.of(hit));

        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                new AdaptiveGraphShadowExpansion.ShadowExpansionReport(
                        0, 0, 0, 0, 0, 0, List.of(), false
                );
        when(shadow.observe(List.of(hit), List.of(hit), SCOPE))
                .thenReturn(report);
        when(online.expand(List.of(hit), report, SCOPE))
                .thenReturn(List.of(hit));
        when(competition.admit(List.of(hit))).thenReturn(List.of(hit));

        when(budget.apply(anyList(), eq("What dose?")))
                .thenAnswer(invocation ->
                        List.copyOf(invocation.getArgument(0))
                );
        when(revalidator.revalidate(anyList(), eq(SCOPE)))
                .thenAnswer(invocation ->
                        List.copyOf(invocation.getArgument(0))
                );
        when(assembler.assemble(anyList()))
                .thenReturn("{\"sources\":[]}");
        when(generation.generate(
                eq("What dose?"),
                eq("{\"sources\":[]}")
        )).thenReturn(answer);

        RagQuestionService service = new RagQuestionService(
                chunker,
                new RagFallbackMessages(
                        new kz.alimbetov.akmai.rag.query.QueryLanguageDetector()
                ),
                planner,
                executor,
                fusion,
                reranker,
                expansion,
                budget,
                new TemporalAuthorityFilter(),
                revalidator,
                assembler,
                new CitationValidator(),
                new AnswerGroundingVerifier(),
                generation,
                learning,
                shadow,
                online,
                competition,
                utility
        );

        return new Fixture(service, learning, generation, utility);
    }

    private RetrievalHit hit(
            String text,
            Map<String, Object> extraMetadata
    ) {
        java.util.LinkedHashMap<String, Object> metadata =
                new java.util.LinkedHashMap<>();
        metadata.put("source", "medicine.md");
        metadata.put("language", "en");
        metadata.put("sectionPath", "Dosage");
        metadata.putAll(extraMetadata);

        return new RetrievalHit(
                RetrievalType.LEXICAL,
                1L,
                "doc-1",
                1L,
                "chunk-1",
                text,
                Map.copyOf(metadata)
        );
    }

    private record Fixture(
            RagQuestionService service,
            AssociationLearningRecorder learning,
            AnswerGenerationService generation,
            AdaptiveGraphUtilityRecorder utility
    ) {
    }
}
