package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.Objects;

public final class NormalizationAssertions {

    private final String actual;
    private final String fixtureId;

    public NormalizationAssertions(String actual) {
        this(actual, null);
    }

    private NormalizationAssertions(String actual, String fixtureId) {
        this.actual = actual;
        this.fixtureId = fixtureId;
    }

    public NormalizationAssertions forFixture(String fixtureId) {
        return new NormalizationAssertions(actual, fixtureId);
    }

    public NormalizationAssertions isEquivalentTo(String expected) {
        if (!Objects.equals(expected, actual)) {
            throw AssuranceFailure.violation(
                    "W-01",
                    fixtureId,
                    "same canonical input and configuration must produce equivalent normalized text",
                    "normalizedText",
                    "expected=" + printable(expected) + ", actual=" + printable(actual)
            );
        }
        return this;
    }

    public NormalizationAssertions isIdempotentWith(String renormalized) {
        if (!Objects.equals(actual, renormalized)) {
            throw AssuranceFailure.violation(
                    "W-01",
                    fixtureId,
                    "normalization must be idempotent for canonical text",
                    "normalizedText",
                    "first=" + printable(actual) + ", second=" + printable(renormalized)
            );
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }

    private String printable(String value) {
        return value == null
                ? "<null>"
                : '"' + value.replace("\n", "\\n") + '"';
    }
}
