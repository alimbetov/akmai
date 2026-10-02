package kz.alimbetov.akmai.knowledge.identifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentifierCapabilityRegistryTest {

    @Test
    void runtimeCapabilitiesAreDerivedFromRegisteredParsers() {
        IdentifierNormalizer normalizer = new IdentifierNormalizer();
        List<IdentifierParser> parsers = List.of(
                new BusinessIdentifierParsers.ContractNumberParser(normalizer),
                new BusinessIdentifierParsers.DocumentNumberParser(normalizer),
                new BusinessIdentifierParsers.OrderNumberParser(normalizer),
                new BusinessIdentifierParsers.InvoiceNumberParser(normalizer),
                new BusinessIdentifierParsers.ApplicationNumberParser(normalizer),
                new BusinessIdentifierParsers.CaseNumberParser(normalizer)
        );

        IdentifierCapabilityRegistry registry =
                new IdentifierCapabilityRegistry(parsers);

        assertThat(registry.supportedTypes()).isEqualTo(Set.of(
                IdentifierType.CONTRACT_NUMBER,
                IdentifierType.DOCUMENT_NUMBER,
                IdentifierType.ORDER_NUMBER,
                IdentifierType.INVOICE_NUMBER,
                IdentifierType.APPLICATION_NUMBER,
                IdentifierType.CASE_NUMBER
        ));
        assertThat(registry.supportedTypes())
                .isEqualTo(parsers.stream()
                        .map(IdentifierParser::type)
                        .collect(java.util.stream.Collectors.toSet()));
        assertThat(registry.isSupported(IdentifierType.CLAIM_NUMBER)).isFalse();
        assertThat(registry.isSupported(IdentifierType.DOCUMENT_ID)).isFalse();
    }
}
