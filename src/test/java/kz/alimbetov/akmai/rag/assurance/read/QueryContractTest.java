package kz.alimbetov.akmai.rag.assurance.read;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.junit.jupiter.api.Test;

class QueryContractTest {

    private final RetrievalPlanner planner = new RetrievalPlanner();

    @Test
    void identifierBearingQueryRetainsExactAndSemanticCapabilities() {
        QueryChunk query = new QueryChunk(
                "r01-mixed-identifier",
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

        var plan = planner.plan(List.of(query));

        assertDoesNotThrow(() -> RagAssertions.plan(plan)
                .forFixture("r01-mixed-identifier")
                .hasLanes(
                        RetrievalType.IDENTIFIER,
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.CONCEPT,
                        RetrievalType.REFERENCE
                )
                .semanticLanesDependOnIdentifierWhenPresent());
    }

    @Test
    void semanticQueryRetainsConfiguredBaselineCapabilities() {
        QueryChunk query = new QueryChunk(
                "r01-semantic",
                0,
                "payment deadline",
                "payment deadline",
                "payment deadline",
                "en",
                List.of()
        );

        var plan = planner.plan(List.of(query));

        assertDoesNotThrow(() -> RagAssertions.plan(plan)
                .forFixture("r01-semantic-baseline")
                .hasLanes(
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.CONCEPT,
                        RetrievalType.REFERENCE
                ));
    }
}
