package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.BusinessIdentifierParsers;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import org.junit.jupiter.api.Test;

class QueryChunkerOriginalPreservationTest {

    @Test
    void retainsAllOriginalChunksWhenExpansionCapIsSmaller() {
        QueryDecomposer decomposer = mock(QueryDecomposer.class);
        when(decomposer.decomposeDetailed("original question")).thenReturn(
                new QueryDecompositionResult(
                        List.of(
                                "original question",
                                "intent one",
                                "intent two",
                                "intent three",
                                "intent four",
                                "intent five",
                                "intent six",
                                "intent seven"
                        ),
                        0
                )
        );
        when(decomposer.decomposeDetailed("generated variant")).thenReturn(
                new QueryDecompositionResult(List.of("generated variant"), 0)
        );

        QueryChunker chunker = chunker(decomposer);
        MultiQueryGenerator generator = mock(MultiQueryGenerator.class);
        AdvancedRetrievalProperties properties = mock(AdvancedRetrievalProperties.class);
        when(generator.generate("original question"))
                .thenReturn(List.of("generated variant"));
        when(properties.maxExpandedChunks()).thenReturn(3);
        chunker.setMultiQueryExpansion(generator, properties);

        List<QueryChunk> result = chunker.chunk("original question");

        assertThat(result).hasSize(8);
        assertThat(result)
                .extracting(QueryChunk::origin)
                .containsOnly(QueryOrigin.ORIGINAL);
        assertThat(result)
                .extracting(QueryChunk::rawText)
                .containsExactly(
                        "original question",
                        "intent one",
                        "intent two",
                        "intent three",
                        "intent four",
                        "intent five",
                        "intent six",
                        "intent seven"
                );
    }

    @Test
    void generatedDuplicatesCannotDisplaceOriginals() {
        QueryDecomposer decomposer = mock(QueryDecomposer.class);
        when(decomposer.decomposeDetailed(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return new QueryDecompositionResult(List.of(value), 0);
        });

        QueryChunker chunker = chunker(decomposer);
        MultiQueryGenerator generator = mock(MultiQueryGenerator.class);
        AdvancedRetrievalProperties properties = mock(AdvancedRetrievalProperties.class);
        when(generator.generate("original"))
                .thenReturn(List.of("original", "unique variant"));
        when(properties.maxExpandedChunks()).thenReturn(2);
        chunker.setMultiQueryExpansion(generator, properties);

        List<QueryChunk> result = chunker.chunk("original");

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().origin()).isEqualTo(QueryOrigin.ORIGINAL);
        assertThat(result.getFirst().rawText()).isEqualTo("original");
        assertThat(result.get(1).origin()).isEqualTo(QueryOrigin.MULTI_QUERY);
        assertThat(result.get(1).rawText()).isEqualTo("unique variant");
    }

    private QueryChunker chunker(QueryDecomposer decomposer) {
        IdentifierNormalizer normalizer = new IdentifierNormalizer();
        IdentifierExtractor extractor = new IdentifierExtractor(List.of(
                new BusinessIdentifierParsers.ContractNumberParser(normalizer),
                new BusinessIdentifierParsers.OrderNumberParser(normalizer)
        ));
        return new QueryChunker(
                new TextNormalizer(),
                extractor,
                new QueryLanguageDetector(),
                decomposer
        );
    }
}
