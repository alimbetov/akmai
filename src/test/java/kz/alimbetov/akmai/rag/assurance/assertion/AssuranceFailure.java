package kz.alimbetov.akmai.rag.assurance.assertion;

final class AssuranceFailure {

    private static final String UNKNOWN_FIXTURE = "<unspecified>";
    private static final String UNKNOWN_SUBJECT = "<unknown>";

    private AssuranceFailure() {
    }

    static AssertionError violation(
            String contractId,
            String fixtureId,
            String invariant,
            String violatingSubject,
            String details
    ) {
        return new AssertionError(
                "[RAG-CONTRACT " + safe(contractId, "UNSPECIFIED") + "]"
                        + " fixture=" + safe(fixtureId, UNKNOWN_FIXTURE)
                        + " invariant=" + safe(invariant, "<unspecified>")
                        + " violating=" + safe(violatingSubject, UNKNOWN_SUBJECT)
                        + " details=" + safe(details, "<none>")
        );
    }

    static String fixture(String fixtureId) {
        return safe(fixtureId, UNKNOWN_FIXTURE);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
