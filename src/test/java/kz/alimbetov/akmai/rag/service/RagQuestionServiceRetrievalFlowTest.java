package kz.alimbetov.akmai.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
import kz.alimbetov.akmai.rag.grounding.SemanticGroundingVerifier;
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
import kz.alimbetov.akmai.rag.retrieval.SourceRef;
import kz.alimbetov.akmai.rag.retrieval.TemporalAuthorityFilter;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RagQuestionServiceRetrievalFlowTest {

    private static final Set<Long> SCOPE = Set.of(1L);
    private static final QueryChunk QUERY = new QueryChunk(
            "q",
            0,
            "question",
            "question",
            "question",
            "en",
            List.of()
    );
    private static final RetrievalPlan PLAN = new RetrievalPlan(List.of());
    private static final RetrievalHit HIT = new RetrievalHit(
            RetrievalType.LEXICAL,
            "doc-1",
            "chunk-1",
            "Supported fact",
            Map.of(
                    "source", "source.md",
                    "language", "en",
                    "sectionPath", "section",
                    "generation", 1L
            )
    );
    private static final AdaptiveGraphShadowExpansion.ShadowExpansionReport GRAPH_REPORT =
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

    @Test
    void groundedRetrievalFlowReturnsAnswerAndSource() {
        Fixture fixture = new Fixture();
        fixture.stubContextPipeline(List.of(HIT));
        CitationValidator.CitationValidation validation = validCitation();
        AnswerGroundingVerifier.GroundingValidation grounding = grounded();
        when(fixture.contextAssembler.assemble(List.of(HIT))).thenReturn("context");
        when(fixture.answerGenerationService.generate("question", "context"))
                .thenReturn("Supported fact [SOURCE 1]");
        when(fixture.citationValidator.validate(
                "Supported fact [SOURCE 1]",
                List.of(HIT)
        )).thenReturn(validation);
        when(fixture.answerGroundingVerifier.verify(
                validation.answer(),
                List.of(HIT),
                "en"
        )).thenReturn(grounding);

        var response = fixture.service().ask("question", SCOPE);

        assertThat(response.answer()).isEqualTo("Supported fact [SOURCE 1]");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).documentId()).isEqualTo("doc-1");
        verify(fixture.utilityRecorder).record(List.of(HIT), validation, true);
        verify(fixture.associationLearningRecorder).record(
                List.of(QUERY),
                SCOPE,
                List.of(HIT),
                validation
        );
    }

    @Test
    void missingAccessScopeFailsClosedBeforeRetrieval() {
        Fixture fixture = new Fixture();

        var response = fixture.service().ask("question", Set.of());

        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(
                fixture.queryChunker,
                fixture.retrievalPlanner,
                fixture.retrievalExecutor
        );
    }

    @Test
    void criticalRetrievalFailureIsUnavailableAndStopsDownstreamPipeline() {
        Fixture fixture = new Fixture();
        fixture.stubPlanning();
        when(fixture.retrievalExecutor.executeDetailed(PLAN, SCOPE)).thenReturn(
                new RetrievalExecutionResult(List.of(), Map.of(), true, true)
        );

        assertThatThrownBy(() -> fixture.service().ask("question", SCOPE))
                .isInstanceOf(RetrievalUnavailableException.class)
                .hasMessage("Knowledge retrieval is temporarily unavailable");

        verifyNoInteractions(
                fixture.resultFusion,
                fixture.reranker,
                fixture.answerGenerationService
        );
    }

    @Test
    void dataAccessFailureDuringContextPipelineIsWrappedAsUnavailable() {
        Fixture fixture = new Fixture();
        fixture.stubPlanning();
        when(fixture.retrievalExecutor.executeDetailed(PLAN, SCOPE)).thenReturn(
                execution(List.of(HIT))
        );
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database unavailable");
        when(fixture.resultFusion.fuse(List.of(HIT), SCOPE)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service().ask("question", SCOPE))
                .isInstanceOf(RetrievalUnavailableException.class)
                .hasCause(failure);

        verifyNoInteractions(fixture.answerGenerationService);
    }

    @Test
    void emptySelectedContextReturnsInsufficientWithoutGeneration() {
        Fixture fixture = new Fixture();
        fixture.stubContextPipeline(List.of());

        var response = fixture.service().ask("question", SCOPE);

        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(
                fixture.contextAssembler,
                fixture.answerGenerationService,
                fixture.citationValidator
        );
    }

    @Test
    void citationRejectionReturnsInsufficientAndSkipsGrounding() {
        Fixture fixture = new Fixture();
        fixture.stubContextPipeline(List.of(HIT));
        when(fixture.contextAssembler.assemble(List.of(HIT))).thenReturn("context");
        when(fixture.answerGenerationService.generate("question", "context"))
                .thenReturn("answer without citation");
        CitationValidator.CitationValidation rejected =
                new CitationValidator.CitationValidation(
                        "answer without citation",
                        List.of(),
                        List.of()
                );
        when(fixture.citationValidator.validate(
                "answer without citation",
                List.of(HIT)
        )).thenReturn(rejected);

        var response = fixture.service().ask("question", SCOPE);

        assertThat(response.sources()).isEmpty();
        verify(fixture.utilityRecorder).record(List.of(HIT), rejected, false);
        verifyNoInteractions(fixture.answerGroundingVerifier);
        verify(fixture.associationLearningRecorder, never()).record(
                anyList(),
                any(),
                anyList(),
                any()
        );
    }

    @Test
    void deterministicGroundingRejectionReturnsInsufficient() {
        Fixture fixture = new Fixture();
        fixture.stubContextPipeline(List.of(HIT));
        CitationValidator.CitationValidation validation = validCitation();
        when(fixture.contextAssembler.assemble(List.of(HIT))).thenReturn("context");
        when(fixture.answerGenerationService.generate("question", "context"))
                .thenReturn(validation.answer());
        when(fixture.citationValidator.validate(validation.answer(), List.of(HIT)))
                .thenReturn(validation);
        when(fixture.answerGroundingVerifier.verify(
                validation.answer(),
                List.of(HIT),
                "en"
        )).thenReturn(new AnswerGroundingVerifier.GroundingValidation(
                false,
                1,
                0,
                List.of()
        ));

        var response = fixture.service().ask("question", SCOPE);

        assertThat(response.sources()).isEmpty();
        verify(fixture.utilityRecorder).record(List.of(HIT), validation, false);
        verify(fixture.associationLearningRecorder, never()).record(
                anyList(),
                any(),
                anyList(),
                any()
        );
    }

    @Test
    void semanticContradictionReturnsInsufficientAndDoesNotLearnAssociations() {
        Fixture fixture = new Fixture();
        fixture.stubContextPipeline(List.of(HIT));
        CitationValidator.CitationValidation validation = validCitation();
        AnswerGroundingVerifier.GroundingValidation grounding = grounded();
        SemanticGroundingVerifier semanticGroundingVerifier =
                mock(SemanticGroundingVerifier.class);
        fixture.service().setSemanticGroundingVerifier(semanticGroundingVerifier);
        when(fixture.contextAssembler.assemble(List.of(HIT))).thenReturn("context");
        when(fixture.answerGenerationService.generate("question", "context"))
                .thenReturn(validation.answer());
        when(fixture.citationValidator.validate(validation.answer(), List.of(HIT)))
                .thenReturn(validation);
        when(fixture.answerGroundingVerifier.verify(
                validation.answer(),
                List.of(HIT),
                "en"
        )).thenReturn(grounding);
        when(semanticGroundingVerifier.verify(grounding, List.of(HIT))).thenReturn(
                new SemanticGroundingVerifier.Verification(
                        SemanticGroundingVerifier.Status.CONTRADICTED,
                        List.of()
                )
        );

        var response = fixture.service().ask("question", SCOPE);

        assertThat(response.sources()).isEmpty();
        verify(fixture.utilityRecorder).record(List.of(HIT), validation, false);
        verify(fixture.associationLearningRecorder, never()).record(
                anyList(),
                any(),
                anyList(),
                any()
        );
    }

    private static RetrievalExecutionResult execution(List<RetrievalHit> hits) {
        return new RetrievalExecutionResult(hits, Map.of(), false, false);
    }

    private static CitationValidator.CitationValidation validCitation() {
        return new CitationValidator.CitationValidation(
                "Supported fact [SOURCE 1]",
                List.of(new SourceRef(
                        1,
                        "doc-1",
                        "chunk-1",
                        "source.md",
                        "en",
                        "section",
                        "unknown"
                )),
                List.of()
        );
    }

    private static AnswerGroundingVerifier.GroundingValidation grounded() {
        return new AnswerGroundingVerifier.GroundingValidation(
                true,
                0,
                0,
                List.of(new AnswerGroundingVerifier.ClaimValidation(
                        "Supported fact",
                        List.of(1),
                        AnswerGroundingVerifier.ClaimStatus.SUPPORTED
                ))
        );
    }

    private static final class Fixture {
        private final QueryChunker queryChunker = mock(QueryChunker.class);
        private final RetrievalPlanner retrievalPlanner = mock(RetrievalPlanner.class);
        private final ParallelRetrievalExecutor retrievalExecutor =
                mock(ParallelRetrievalExecutor.class);
        private final ResultFusion resultFusion = mock(ResultFusion.class);
        private final Reranker reranker = mock(Reranker.class);
        private final KnowledgeExpansion knowledgeExpansion =
                mock(KnowledgeExpansion.class);
        private final ContextBudget contextBudget = mock(ContextBudget.class);
        private final PublishedContextRevalidator contextRevalidator =
                mock(PublishedContextRevalidator.class);
        private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
        private final CitationValidator citationValidator = mock(CitationValidator.class);
        private final AnswerGroundingVerifier answerGroundingVerifier =
                mock(AnswerGroundingVerifier.class);
        private final AnswerGenerationService answerGenerationService =
                mock(AnswerGenerationService.class);
        private final AssociationLearningRecorder associationLearningRecorder =
                mock(AssociationLearningRecorder.class);
        private final AdaptiveGraphShadowExpansion shadowExpansion =
                mock(AdaptiveGraphShadowExpansion.class);
        private final AdaptiveGraphOnlineExpansion onlineExpansion =
                mock(AdaptiveGraphOnlineExpansion.class);
        private final AdaptiveGraphCompetitiveAdmission competitiveAdmission =
                mock(AdaptiveGraphCompetitiveAdmission.class);
        private final AdaptiveGraphUtilityRecorder utilityRecorder =
                mock(AdaptiveGraphUtilityRecorder.class);
        private final RagQuestionService service = createService();

        private RagQuestionService service() {
            return service;
        }

        private void stubPlanning() {
            when(queryChunker.chunk("question")).thenReturn(List.of(QUERY));
            when(retrievalPlanner.plan(eq(List.of(QUERY)), anyString())).thenReturn(PLAN);
        }

        private void stubContextPipeline(List<RetrievalHit> selected) {
            stubPlanning();
            when(retrievalExecutor.executeDetailed(PLAN, SCOPE)).thenReturn(
                    execution(List.of(HIT))
            );
            when(resultFusion.fuse(List.of(HIT), SCOPE)).thenReturn(List.of(HIT));
            when(reranker.rerank(List.of(HIT), "question")).thenReturn(List.of(HIT));
            when(knowledgeExpansion.expand(List.of(HIT), SCOPE)).thenReturn(List.of(HIT));
            when(shadowExpansion.observe(List.of(HIT), List.of(HIT), SCOPE))
                    .thenReturn(GRAPH_REPORT);
            when(onlineExpansion.expand(List.of(HIT), GRAPH_REPORT, SCOPE))
                    .thenReturn(List.of(HIT));
            when(competitiveAdmission.admit(List.of(HIT))).thenReturn(List.of(HIT));
            when(contextBudget.apply(List.of(HIT), "question")).thenReturn(List.of(HIT));
            when(contextRevalidator.revalidate(List.of(HIT), SCOPE)).thenReturn(selected);
        }

        private RagQuestionService createService() {
            return new RagQuestionService(
                    queryChunker,
                    new RagFallbackMessages(
                            new kz.alimbetov.akmai.rag.query.QueryLanguageDetector()
                    ),
                    retrievalPlanner,
                    retrievalExecutor,
                    resultFusion,
                    reranker,
                    knowledgeExpansion,
                    contextBudget,
                    new TemporalAuthorityFilter(),
                    contextRevalidator,
                    contextAssembler,
                    citationValidator,
                    answerGroundingVerifier,
                    answerGenerationService,
                    associationLearningRecorder,
                    shadowExpansion,
                    onlineExpansion,
                    competitiveAdmission,
                    utilityRecorder
            );
        }
    }
}
