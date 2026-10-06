package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.List;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;

public final class CitationAssertions {

    private final CitationValidator.CitationValidation actual;
    private final String fixtureId;

    public CitationAssertions(CitationValidator.CitationValidation actual) {
        this(actual, null);
    }

    private CitationAssertions(
            CitationValidator.CitationValidation actual,
            String fixtureId
    ) {
        this.actual = actual;
        this.fixtureId = fixtureId;
    }

    public CitationAssertions forFixture(String fixtureId) {
        return new CitationAssertions(actual, fixtureId);
    }

    public CitationAssertions hasNoInvalidSourceNumbers() {
        List<Integer> invalid = actual == null || actual.invalidSourceNumbers() == null
                ? List.of()
                : actual.invalidSourceNumbers();
        if (actual == null || !invalid.isEmpty()) {
            throw AssuranceFailure.violation(
                    "R-11",
                    fixtureId,
                    "generated source markers must resolve inside final bounded context",
                    invalid.isEmpty() ? null : invalid.toString(),
                    actual == null ? "citation validation is null" : "invalidSourceNumbers=" + invalid
            );
        }
        return this;
    }

    public CitationAssertions hasAtLeastOneCitedSource() {
        if (actual == null
                || actual.citedSources() == null
                || actual.citedSources().isEmpty()) {
            throw AssuranceFailure.violation(
                    "R-11",
                    fixtureId,
                    "grounded factual output must cite at least one final-context source",
                    null,
                    "no cited sources"
            );
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }
}
