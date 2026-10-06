package kz.alimbetov.akmai.rag.assurance.assertion;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;

public final class PlanAssertions {

    private final RetrievalPlan actual;
    private final String fixtureId;

    public PlanAssertions(RetrievalPlan actual) {
        this(actual, null);
    }

    private PlanAssertions(RetrievalPlan actual, String fixtureId) {
        this.actual = actual;
        this.fixtureId = fixtureId;
    }

    public PlanAssertions forFixture(String fixtureId) {
        return new PlanAssertions(actual, fixtureId);
    }

    public PlanAssertions hasLane(RetrievalType type) {
        if (actual == null || actual.steps() == null || actual.steps().stream()
                .noneMatch(step -> step != null && step.type() == type)) {
            throw AssuranceFailure.violation(
                    "R-01",
                    fixtureId,
                    "retrieval plan must preserve required retrieval capability",
                    type == null ? null : type.name(),
                    "requiredLane=" + type + ", actualLanes=" + laneTypes()
            );
        }
        return this;
    }

    public PlanAssertions hasLanes(RetrievalType... types) {
        if (types == null || types.length == 0) {
            throw new IllegalArgumentException("types must not be empty");
        }
        Arrays.stream(types).forEach(this::hasLane);
        return this;
    }

    public PlanAssertions semanticLanesDependOnIdentifierWhenPresent() {
        RetrievalStep identifier = actual == null || actual.steps() == null
                ? null
                : actual.steps().stream()
                        .filter(step -> step != null
                                && step.type() == RetrievalType.IDENTIFIER)
                        .findFirst()
                        .orElse(null);
        if (identifier == null) {
            throw AssuranceFailure.violation(
                    "R-01",
                    fixtureId,
                    "identifier-bearing query must retain an identifier retrieval lane",
                    "IDENTIFIER",
                    "identifier lane is absent"
            );
        }

        Set<RetrievalType> semantic = EnumSet.of(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.CONCEPT
        );
        for (RetrievalStep step : actual.steps()) {
            if (step != null && semantic.contains(step.type())) {
                List<String> dependencies = step.dependsOn() == null
                        ? List.of()
                        : step.dependsOn();
                if (!dependencies.contains(identifier.id())) {
                    throw AssuranceFailure.violation(
                            "R-01",
                            fixtureId,
                            "semantic lanes for mixed identifier queries must preserve identifier dependency",
                            step.type().name(),
                            "identifierStep=" + identifier.id()
                                    + ", dependencies=" + dependencies
                    );
                }
            }
        }
        return this;
    }

    public String fixtureId() {
        return AssuranceFailure.fixture(fixtureId);
    }

    private Set<RetrievalType> laneTypes() {
        if (actual == null || actual.steps() == null) {
            return Set.of();
        }
        EnumSet<RetrievalType> types = EnumSet.noneOf(RetrievalType.class);
        actual.steps().stream()
                .filter(java.util.Objects::nonNull)
                .map(RetrievalStep::type)
                .filter(java.util.Objects::nonNull)
                .forEach(types::add);
        return Set.copyOf(types);
    }
}
