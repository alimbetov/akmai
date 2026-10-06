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

    public GroundingAssertions isRejected() {
        if (actual == null || actual.grounded()) {
            throw AssuranceFailure.violation(
                    "R-12",
                    fixtureId,
                    "unsupported factual output must be rejected as ungrounded",
                    "answer",
                    actual == null ? "grounding validation is null" : "grounded=true"
            );
        }
        return this;
    }

    public GroundingAssertions hasUnsupportedClaims() {
        if (actual == null || actual.unsupportedClaimCount() < 1) {
            throw AssuranceFailure.violation(
                    "R-12",
                    fixtureId,
                    "unsupported factual output must expose at least one unsupported claim",
                    "answer",
                    actual == null
                            ? "grounding validation is null"
                            : "unsupportedClaimCount=" + actual.unsupportedClaimCount()
            );
        }
        return this;
    }

    public GroundingAssertions hasNumericMismatch() {
        if (actual == null || actual.numericMismatchCount() < 1) {
            throw AssuranceFailure.violation(
                    "R-12",
                    fixtureId,
                    "numeric drift must be rejected by grounding",
                    "answer",
                    actual == null
                            ? "grounding validation is null"
                            : "numericMismatchCount=" + actual.numericMismatchCount()
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
