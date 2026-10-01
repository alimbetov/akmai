package kz.alimbetov.akmai.rag.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class RetrievalPlannerTest {

    private final RetrievalPlanner planner = new RetrievalPlanner();

    @Test
    void mixedIdentifierQueryMakesSemanticRetrievalDependOnIdentifierLookup() {
        QueryChunk query = new QueryChunk(
                "q1",
                0,
                "условия договора KZ-2026-001847",
                "условия договора KZ-2026-001847",
                "условия договора",
                List.of(new DetectedIdentifier(
                        IdentifierType.CONTRACT_NUMBER,
                        "KZ-2026-001847",
                        "KZ2026001847",
                        "договор KZ-2026-001847"
                ))
        );

        RetrievalPlan plan = planner.plan(List.of(query));

        RetrievalStep identifier = plan.steps().stream()
                .filter(step -> step.type() == RetrievalType.IDENTIFIER)
                .findFirst()
                .orElseThrow();

        assertThat(plan.steps())
                .filteredOn(step -> step.type() == RetrievalType.VECTOR
                        || step.type() == RetrievalType.LEXICAL)
                .allSatisfy(step -> assertThat(step.dependsOn())
                        .containsExactly(identifier.id()));
    }

    @Test
    void semanticQueryCreatesIndependentVectorAndLexicalRoots() {
        QueryChunk query = new QueryChunk(
                "q1", 0, "условия расторжения", "условия расторжения",
                "условия расторжения", List.of()
        );

        RetrievalPlan plan = planner.plan(List.of(query));

        assertThat(plan.steps())
                .extracting(RetrievalStep::type)
                .containsExactly(RetrievalType.VECTOR, RetrievalType.LEXICAL);
        assertThat(plan.steps()).allSatisfy(
                step -> assertThat(step.dependsOn()).isEmpty()
        );
    }
}
