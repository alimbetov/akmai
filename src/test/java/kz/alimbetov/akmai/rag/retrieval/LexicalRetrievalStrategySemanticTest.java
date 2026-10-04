package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
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

class LexicalRetrievalStrategySemanticTest {

    private static final Set<Long> SCOPE = Set.of(1L);

    @Test
    void morphologyDerivedConceptAddsBoundedSemanticLexicalHit() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        SemanticQueryAnalyzer analyzer =
                mock(SemanticQueryAnalyzer.class);
        LexicalRetrievalStrategy strategy =
                new LexicalRetrievalStrategy(
                        repository,
                        properties(),
                        analyzer
                );

        QueryChunk query = query(
                "risk weighted asset exposure",
                "en"
        );
        List<SearchProjection> baseline = List.of(
                projection("base-1", 0),
                projection("base-2", 1),
                projection("base-3", 2)
        );
        SearchProjection semantic = projection("semantic-1", 3);

        when(repository.searchLexical(
                "risk weighted asset exposure",
                "en",
                List.of(),
                SCOPE,
                4
        )).thenReturn(baseline);
        when(analyzer.analyze("risk weighted asset exposure"))
                .thenReturn(analysis(
                        "en",
                        "risk weighted assets",
                        SemanticMatchMode.LEMMA
                ));
        when(repository.searchLexical(
                "risk weighted assets",
                "en",
                List.of(),
                SCOPE,
                4
        )).thenReturn(List.of(semantic));

        List<RetrievalHit> result = strategy.retrieve(
                query,
                new RetrievalContext(List.of(), SCOPE)
        );

        assertThat(result)
                .extracting(RetrievalHit::chunkId)
                .containsExactly(
                        "base-1",
                        "base-2",
                        "base-3",
                        "semantic-1"
                );
        assertThat(result.getLast().metadata())
                .containsEntry("semanticLexicalExpansion", true);
    }

    @Test
    void exactPreferredPhraseDoesNotRepeatLexicalSearch() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        SemanticQueryAnalyzer analyzer =
                mock(SemanticQueryAnalyzer.class);
        LexicalRetrievalStrategy strategy =
                new LexicalRetrievalStrategy(
                        repository,
                        properties(),
                        analyzer
                );

        when(repository.searchLexical(
                "capital adequacy ratio",
                "en",
                List.of(),
                SCOPE,
                4
        )).thenReturn(List.of(projection("base", 0)));
        when(analyzer.analyze("capital adequacy ratio"))
                .thenReturn(analysis(
                        "en",
                        "capital adequacy ratio",
                        SemanticMatchMode.EXACT
                ));

        List<RetrievalHit> result = strategy.retrieve(
                query("capital adequacy ratio", "en"),
                new RetrievalContext(List.of(), SCOPE)
        );

        assertThat(result)
                .extracting(RetrievalHit::chunkId)
                .containsExactly("base");
        verify(repository, times(1)).searchLexical(
                eq("capital adequacy ratio"),
                eq("en"),
                anyList(),
                eq(SCOPE),
                eq(4)
        );
    }

    @Test
    void semanticLexicalRolloutDoesNotExpandOtherLanguagesYet() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        SemanticQueryAnalyzer analyzer =
                mock(SemanticQueryAnalyzer.class);
        LexicalRetrievalStrategy strategy =
                new LexicalRetrievalStrategy(
                        repository,
                        properties(),
                        analyzer
                );

        when(repository.searchLexical(
                "risikogewichtetes aktiv",
                "de",
                List.of(),
                SCOPE,
                4
        )).thenReturn(List.of(projection("base", 0)));
        when(analyzer.analyze("risikogewichtetes aktiv"))
                .thenReturn(analysis(
                        "de",
                        "risikogewichtete aktiva",
                        SemanticMatchMode.LEMMA
                ));

        List<RetrievalHit> result = strategy.retrieve(
                query("risikogewichtetes aktiv", "de"),
                new RetrievalContext(List.of(), SCOPE)
        );

        assertThat(result)
                .extracting(RetrievalHit::chunkId)
                .containsExactly("base");
        verify(repository, times(1)).searchLexical(
                eq("risikogewichtetes aktiv"),
                eq("de"),
                anyList(),
                eq(SCOPE),
                eq(4)
        );
    }

    private RetrievalProperties properties() {
        return new RetrievalProperties(
                4,
                16,
                8,
                0.2,
                4,
                4,
                4,
                60,
                2,
                1,
                2,
                2048,
                8,
                4,
                true,
                8,
                Duration.ofSeconds(1),
                0.4
        );
    }

    private QueryChunk query(String text, String language) {
        return new QueryChunk(
                "q",
                0,
                text,
                text,
                text,
                language,
                List.of()
        );
    }

    private SemanticQueryAnalysis analysis(
            String language,
            String phrase,
            SemanticMatchMode mode
    ) {
        return new SemanticQueryAnalysis(
                language,
                language,
                mode.confidence(),
                List.of("finance_banking"),
                List.of(new SemanticConceptMatch(
                        "finance_banking.risk_capital.risk_weighted_assets",
                        "finance_banking",
                        "risk_capital",
                        phrase,
                        3.0 * mode.confidence(),
                        mode
                ))
        );
    }

    private SearchProjection projection(
            String chunkId,
            int chunkIndex
    ) {
        return new SearchProjection(
                chunkId,
                "doc-" + chunkId,
                1L,
                1L,
                null,
                chunkIndex,
                "text " + chunkId,
                "embedding " + chunkId,
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of(),
                1
        );
    }
}
