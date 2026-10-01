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
        return new QueryChunker(new TextNormalizer(), extractor, new QueryLanguageDetector());
    }

    @Test
    void detectsTargetLanguages() {
        assertThat(chunker().chunk("Құжаттың төлем мерзімі қандай?").getFirst().language())
                .isEqualTo("kk");
        assertThat(chunker().chunk("Какой срок оплаты?").getFirst().language())
                .isEqualTo("ru");
        assertThat(chunker().chunk("What is the payment deadline?").getFirst().language())
                .isEqualTo("en");
        assertThat(chunker().chunk("付款期限是什么？").getFirst().language())
                .isEqualTo("zh");
    }

    @Test
    void usesSameIdentifierParsersForQuestionAndSeparatesMultipleIdentifiers() {
        var chunks = chunker().chunk(
                "Условия договора KZ-2026-001847. Сравни с договором KZ-2025-009812."
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
