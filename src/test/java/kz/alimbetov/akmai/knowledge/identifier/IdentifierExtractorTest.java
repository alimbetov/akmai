package kz.alimbetov.akmai.knowledge.identifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IdentifierExtractorTest {

    private final IdentifierNormalizer normalizer = new IdentifierNormalizer();
    private final IdentifierExtractor extractor = new IdentifierExtractor(List.of(
            new BusinessIdentifierParsers.ContractNumberParser(normalizer),
            new BusinessIdentifierParsers.OrderNumberParser(normalizer),
            new BusinessIdentifierParsers.InvoiceNumberParser(normalizer),
            new BusinessIdentifierParsers.ApplicationNumberParser(normalizer),
            new BusinessIdentifierParsers.CaseNumberParser(normalizer),
            new BusinessIdentifierParsers.DocumentNumberParser(normalizer)
    ));

    @Test
    void extractsAndNormalizesBusinessIdentifiers() {
        var values = extractor.extract(
                "Договор № KZ-2026-001847. Заказ ORD-883921. Заявка APP-55192."
        );

        assertThat(values)
                .extracting(DetectedIdentifier::type)
                .contains(
                        IdentifierType.CONTRACT_NUMBER,
                        IdentifierType.ORDER_NUMBER,
                        IdentifierType.APPLICATION_NUMBER
                );

        assertThat(values)
                .filteredOn(value -> value.type() == IdentifierType.CONTRACT_NUMBER)
                .singleElement()
                .extracting(DetectedIdentifier::normalizedValue)
                .isEqualTo("KZ2026001847");
    }
}
