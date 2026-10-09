package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.BusinessIdentifierParsers;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import org.junit.jupiter.api.Test;

class LargeQuerySmokeTest {

    @Test
    void largeMultiIntentQuestionRemainsBoundedAndDeduplicated() {
        QueryChunker chunker = chunker();
        String question = IntStream.rangeClosed(1, 40)
                .mapToObj(index -> "Please explain requirement " + index
                        + " for contract KZ-2026-001847, including evidence, exceptions and monitoring obligations.")
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();

        List<QueryChunk> chunks = chunker.chunk(question);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isLessThanOrEqualTo(QueryDecomposer.MAX_SEGMENTS);
        assertThat(chunks)
                .extracting(QueryChunk::normalizedText)
                .doesNotHaveDuplicates();
        assertThat(chunks)
                .flatExtracting(QueryChunk::identifiers)
                .anySatisfy(identifier ->
                        assertThat(identifier.normalizedValue())
                                .isEqualTo("KZ-2026-001847"));
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.rawText()).isNotBlank();
            assertThat(chunk.normalizedText()).isNotBlank();
            assertThat(chunk.language()).isEqualTo("en");
        });
    }

    private QueryChunker chunker() {
        IdentifierNormalizer normalizer = new IdentifierNormalizer();
        IdentifierExtractor extractor = new IdentifierExtractor(List.of(
                new BusinessIdentifierParsers.ContractNumberParser(normalizer),
                new BusinessIdentifierParsers.OrderNumberParser(normalizer)
        ));
        return new QueryChunker(
                new TextNormalizer(),
                extractor,
                new QueryLanguageDetector()
        );
    }
}
