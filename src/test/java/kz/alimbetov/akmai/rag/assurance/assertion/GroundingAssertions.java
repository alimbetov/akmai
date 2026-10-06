package kz.alimbetov.akmai.rag.assurance.assertion;

import kz.alimbetov.akmai.rag.retrieval.AnswerGroundingVerifier;

public final class GroundingAssertions {

    private final AnswerGroundingVerifier.GroundingValidation actual;
    private final String fixtureId;

    public GroundingAssertions(AnswerGroundingVerifier.GroundingValidation actual) {
        this(actual, null);
    }

    private GroundingAssertions(
            AnswerGroundingVerifier.GroundingValidation actual,
            String fixtureId
    ) {
        this.actual = actual;
        this.fixtureId = fixtureId;
    }

    public GroundingAssertions forFixture(String fixtureId) {
        return new GroundingAssertions(actual, fixtureId);
    }

    public GroundingAssertions isGrounded() {
        if (actual == null || !actual.grounded()) {
            throw AssuranceFailure.violation(
                    "R-12",
                    fixtureId,
                    "successful answer must be grounded",
                    "answer",
                    actual == null
                            ? "grounding validation is null"
                            : "unsupportedClaims=" + actual.unsupportedClaimCount()
                                    + ", numericMismatches=" + actual.numericMismatchCount()
            );
        }
        return this;
    }

    public GroundingAssertions isGroundedOrAbstains(boolean abstained) {
        if ((actual == null || !actual.grounded()) && !abstained) {
            throw AssuranceFailure.violation(
                    "R-12",
                    fixtureId,
                    "terminal answer must be grounded or explicitly abstained/rejected",
                    "answer",
                    actual == null
                            ? "grounding validation is null and abstained=false"
                            : "grounded=false, abstained=false, unsupportedClaims="
                                    + actual.unsupportedClaimCount()
                                    + ", numericMismatches=" + actual.numericMismatchCount()
            );
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }
}
