package kz.alimbetov.akmai.rag.assurance.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import org.junit.jupiter.api.Test;

class NormalizationContractTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void sameInputProducesEquivalentCanonicalText() {
        String raw = "  Article 25\r\n\r\n\r\n  Payment\tterms  ";

        String first = normalizer.normalize(raw);
        String second = normalizer.normalize(raw);

        assertDoesNotThrow(() -> RagAssertions.normalization(first)
                .forFixture("w01-same-input")
                .isEquivalentTo(second)
                .isIdempotentWith(normalizer.normalize(first)));
    }

    @Test
    void canonicallyEquivalentUnicodeAndLineEndingsConverge() {
        String decomposed = "  Cafe\u0301\r\n\r\n\r\nDose:\t10 mg  ";
        String precomposed = "Caf\u00e9\n\nDose: 10 mg";

        String normalizedDecomposed = normalizer.normalize(decomposed);
        String normalizedPrecomposed = normalizer.normalize(precomposed);

        assertDoesNotThrow(() -> RagAssertions.normalization(normalizedDecomposed)
                .forFixture("w01-unicode-line-endings")
                .isEquivalentTo("Caf\u00e9\n\nDose: 10 mg")
                .isEquivalentTo(normalizedPrecomposed)
                .isIdempotentWith(normalizer.normalize(normalizedDecomposed)));
    }

    @Test
    void nullAndWhitespaceOnlyInputsRemainDeterministic() {
        String normalizedNull = normalizer.normalize(null);
        String normalizedWhitespace = normalizer.normalize(" \t \r\n  ");

        assertDoesNotThrow(() -> RagAssertions.normalization(normalizedNull)
                .forFixture("w01-empty-boundary")
                .isEquivalentTo("")
                .isIdempotentWith(normalizer.normalize(normalizedNull)));

        assertDoesNotThrow(() -> RagAssertions.normalization(normalizedWhitespace)
                .forFixture("w01-whitespace-boundary")
                .isEquivalentTo("")
                .isIdempotentWith(normalizer.normalize(normalizedWhitespace)));
    }
}
