package kz.alimbetov.akmai.knowledge.identifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IdentifierAcceptanceRegressionTest {

    private final IdentifierNormalizer normalizer = new IdentifierNormalizer();

    @Test
    void significantSeparatorsNeverCollapseToSameExactIdentity() {
        assertThat(normalizer.normalize(
                IdentifierType.CONTRACT_NUMBER,
                "AB-12"
        )).isEqualTo("AB-12");
        assertThat(normalizer.normalize(
                IdentifierType.CONTRACT_NUMBER,
                "AB/12"
        )).isEqualTo("AB/12");
        assertThat(normalizer.normalize(
                IdentifierType.CONTRACT_NUMBER,
                "AB-12"
        )).isNotEqualTo(normalizer.normalize(
                IdentifierType.CONTRACT_NUMBER,
                "AB/12"
        ));
    }

    @Test
    void canonicallyEquivalentUnicodeUsesSameIdentifierIdentity() {
        String nfc = "É-12";
        String nfd = "É-12";

        assertThat(normalizer.normalize(
                IdentifierType.CONTRACT_NUMBER,
                nfd
        )).isEqualTo(normalizer.normalize(
                IdentifierType.CONTRACT_NUMBER,
                nfc
        ));
    }

    @Test
    void ordinaryBusinessProseDoesNotBecomeExactIdentifiers() {
        IdentifierExtractor extractor = extractor();

        assertThat(extractor.extract(
                "contract termination, order status, case management, document archive"
        )).isEmpty();

        assertThat(extractor.extract("contract No. AB-12"))
                .extracting(DetectedIdentifier::normalizedValue)
                .containsExactly("AB-12");
    }

    @Test
    void oversizedIdentifierTokenIsRejectedBeforePersistenceBoundary() {
        IdentifierExtractor extractor = extractor();
        String oversized = "A".repeat(501) + "1";

        assertThat(extractor.extract("contract No. " + oversized)).isEmpty();
        assertThat(normalizer.normalize(
                IdentifierType.CONTRACT_NUMBER,
                oversized
        )).isEmpty();
    }

    private IdentifierExtractor extractor() {
        return new IdentifierExtractor(List.of(
                new BusinessIdentifierParsers.ContractNumberParser(normalizer),
                new BusinessIdentifierParsers.OrderNumberParser(normalizer),
                new BusinessIdentifierParsers.CaseNumberParser(normalizer),
                new BusinessIdentifierParsers.DocumentNumberParser(normalizer)
        ));
    }
}
