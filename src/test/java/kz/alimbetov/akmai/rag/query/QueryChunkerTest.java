package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.BusinessIdentifierParsers;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import org.junit.jupiter.api.Test;

class QueryChunkerTest {

    private QueryChunker chunker() {
        var normalizer = new IdentifierNormalizer();
        var extractor = new IdentifierExtractor(List.of(
                new BusinessIdentifierParsers.ContractNumberParser(normalizer),
                new BusinessIdentifierParsers.OrderNumberParser(normalizer)
        ));
        return new QueryChunker(new TextNormalizer(), extractor);
    }

    @Test
    void usesSameIdentifierParsersForQuestionAndSeparatesMultipleIdentifiers() {
        var chunks = chunker().chunk(
                "Сравни условия договоров KZ-2026-001847 и KZ-2025-009812."
        );

        assertThat(chunks).hasSize(2);
        assertThat(chunks)
                .flatExtracting(QueryChunk::identifiers)
                .extracting(value -> value.type())
                .containsOnly(IdentifierType.CONTRACT_NUMBER);
        assertThat(chunks)
                .flatExtracting(QueryChunk::identifiers)
                .extracting(value -> value.normalizedValue())
                .containsExactlyInAnyOrder("KZ2026001847", "KZ2025009812");
    }
}
