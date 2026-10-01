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
                "ru",
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
                "условия расторжения", "ru", List.of()
        );

        RetrievalPlan plan = planner.plan(List.of(query));

        assertThat(plan.steps())
                .extracting(RetrievalStep::type)
                .containsExactly(
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.REFERENCE
                );

        RetrievalStep vector = plan.steps().get(0);
        RetrievalStep lexical = plan.steps().get(1);
        RetrievalStep reference = plan.steps().get(2);

        assertThat(vector.dependsOn()).isEmpty();
        assertThat(lexical.dependsOn()).isEmpty();
        assertThat(reference.dependsOn())
                .containsExactly(vector.id(), lexical.id());
    }

    @Test
    void sameQueryContentProducesStableStepIdsAndDependencies() {
        QueryChunk first = new QueryChunk(
                "random-a",
                0,
                "условия расторжения",
                "условия расторжения",
                "условия расторжения",
                "ru",
                List.of()
        );
        QueryChunk second = new QueryChunk(
                "random-b",
                99,
                "условия расторжения",
                "условия расторжения",
                "условия расторжения",
                "ru",
                List.of()
        );

        RetrievalPlan firstPlan = planner.plan(List.of(first));
        RetrievalPlan secondPlan = planner.plan(List.of(second));

        assertThat(firstPlan.steps())
                .extracting(RetrievalStep::id)
                .containsExactlyElementsOf(
                        secondPlan.steps().stream().map(RetrievalStep::id).toList()
                );
        assertThat(firstPlan.steps())
                .extracting(RetrievalStep::dependsOn)
                .containsExactlyElementsOf(
                        secondPlan.steps().stream().map(RetrievalStep::dependsOn).toList()
                );
    }

    @Test
    void duplicateRetrievalUnitsDoNotMultiplyPlanBranches() {
        QueryChunk first = new QueryChunk(
                "q1", 0, "payment deadline", "payment deadline",
                "payment deadline", "en", List.of()
        );
        QueryChunk duplicate = new QueryChunk(
                "q2", 1, "payment deadline", "payment deadline",
                "payment deadline", "en", List.of()
        );

        RetrievalPlan plan = planner.plan(List.of(first, duplicate));

        assertThat(plan.steps())
                .extracting(RetrievalStep::type)
                .containsExactly(
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.REFERENCE
                );
    }

    @Test
    void equivalentMultilingualQueriesPreservePlanShape() {
        List<QueryChunk> queries = List.of(
                new QueryChunk("ru", 0, "срок оплаты", "срок оплаты", "срок оплаты", "ru", List.of()),
                new QueryChunk("kk", 0, "төлем мерзімі", "төлем мерзімі", "төлем мерзімі", "kk", List.of()),
                new QueryChunk("en", 0, "payment deadline", "payment deadline", "payment deadline", "en", List.of()),
                new QueryChunk("zh", 0, "付款期限", "付款期限", "付款期限", "zh", List.of())
        );

        for (QueryChunk query : queries) {
            assertThat(planner.plan(List.of(query)).steps())
                    .extracting(RetrievalStep::type)
                    .containsExactly(
                            RetrievalType.VECTOR,
                            RetrievalType.LEXICAL,
                            RetrievalType.REFERENCE
                    );
        }
    }

    @Test
    void identifierOrderDoesNotChangeStableStepIds() {
        DetectedIdentifier contract = new DetectedIdentifier(
                IdentifierType.CONTRACT_NUMBER,
                "KZ-2026-001847",
                "KZ2026001847",
                "договор KZ-2026-001847"
        );
        DetectedIdentifier order = new DetectedIdentifier(
                IdentifierType.ORDER_NUMBER,
                "ORD-2026-42",
                "ORD202642",
                "заказ ORD-2026-42"
        );

        QueryChunk first = new QueryChunk(
                "q1", 0, "условия", "условия", "условия", "ru",
                List.of(contract, order)
        );
        QueryChunk second = new QueryChunk(
                "q2", 0, "условия", "условия", "условия", "ru",
                List.of(order, contract)
        );

        assertThat(planner.plan(List.of(first)).steps())
                .extracting(RetrievalStep::id)
                .containsExactlyElementsOf(
                        planner.plan(List.of(second)).steps().stream()
                                .map(RetrievalStep::id)
                                .toList()
                );
    }

}
