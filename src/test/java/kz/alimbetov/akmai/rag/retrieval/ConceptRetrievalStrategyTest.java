package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptMatch;
import kz.alimbetov.akmai.knowledge.semantic.SemanticMatchMode;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConceptRetrievalStrategyTest {

    private static final String CONCEPT =
            "finance_banking.lending_credit.corporate_credit_facility";

    @Mock
    PublishedSearchProjectionReader repository;

    @Mock
    SemanticQueryAnalyzer analyzer;

    @Test
    void retrievesPublishedCandidatesByCanonicalConceptId() {
        QueryChunk query = query("Explain corporate credit facility.");
        when(analyzer.analyze(query.semanticText()))
                .thenReturn(new SemanticQueryAnalysis(
                        "en",
                        "en",
                        1.0,
                        List.of("finance_banking"),
                        List.of(new SemanticConceptMatch(
                                CONCEPT,
                                "finance_banking",
                                "lending_credit",
                                "corporate credit facility",
                                3.0,
                                SemanticMatchMode.EXACT
                        ))
                ));
        when(repository.searchSemanticConcepts(
                List.of(CONCEPT),
                List.of(),
                Set.of(1L),
                4
        )).thenReturn(List.of(projection()));

        ConceptRetrievalStrategy subject =
                new ConceptRetrievalStrategy(
                        repository,
                        analyzer,
                        RetrievalTestProperties.defaults()
                );

        List<RetrievalHit> hits = subject.retrieve(
                query,
                new RetrievalContext(List.of(), Set.of(1L))
        );

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().type())
                .isEqualTo(RetrievalType.CONCEPT);
        assertThat(hits.getFirst().metadata())
                .containsEntry("semanticConceptRetrieval", true)
                .containsEntry("semanticConceptOverlap", 1)
                .containsEntry("score", 1.0);
        assertThat(hits.getFirst().metadata().get("semanticQueryConcepts"))
                .isEqualTo(List.of(CONCEPT));

        verify(repository).searchSemanticConcepts(
                List.of(CONCEPT),
                List.of(),
                Set.of(1L),
                4
        );
    }

    @Test
    void semanticAnalysisFailureIsFailSoft() {
        QueryChunk query = query("Explain corporate credit facility.");
        when(analyzer.analyze(query.semanticText()))
                .thenThrow(new IllegalStateException("semantic unavailable"));

        ConceptRetrievalStrategy subject =
                new ConceptRetrievalStrategy(
                        repository,
                        analyzer,
                        RetrievalTestProperties.defaults()
                );

        assertThat(subject.retrieve(
                query,
                new RetrievalContext(List.of(), Set.of(1L))
        )).isEmpty();
    }

    private QueryChunk query(String text) {
        return new QueryChunk(
                "q1",
                0,
                text,
                text,
                text,
                "en",
                List.of()
        );
    }

    private SearchProjection projection() {
        return new SearchProjection(
                "chunk-ru",
                "doc",
                3L,
                1L,
                null,
                0,
                "Корпоративная кредитная линия для заемщика.",
                "Корпоративная кредитная линия для заемщика.",
                "ru",
                KnowledgeDomain.GENERAL,
                "lending",
                List.of(),
                List.of(),
                Map.of("semanticConcepts", List.of(CONCEPT)),
                2
        );
    }
}
