package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.BusinessIdentifierParsers;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.junit.jupiter.api.Test;

class PhaseBRetrievalAcceptanceTest {

    private final RetrievalPlanner planner = new RetrievalPlanner();

    @Test
    void multiIntentQueryProducesBoundedDeterministicRetrievalPlan() {
        List<QueryChunk> chunks = chunker().chunk(
                "What dosage applies and what monitoring is required?"
        );

        RetrievalPlan first = planner.plan(chunks);
        RetrievalPlan second = planner.plan(chunks);

        assertThat(chunks).hasSize(3);
        assertThat(first.steps()).hasSize(12);
        assertThat(first.steps())
                .extracting(RetrievalStep::type)
                .containsExactly(
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.CONCEPT,
                        RetrievalType.REFERENCE,
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.CONCEPT,
                        RetrievalType.REFERENCE,
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL,
                        RetrievalType.CONCEPT,
                        RetrievalType.REFERENCE
                );
        assertThat(first.steps())
                .extracting(RetrievalStep::id)
                .containsExactlyElementsOf(
                        second.steps().stream().map(RetrievalStep::id).toList()
                );
    }

    @Test
    void identifierAndSemanticQueryPreservesIdentifierDependencyChain() {
        List<QueryChunk> chunks = chunker().chunk(
                "Какие условия расторжения договора KZ-2026-001847 и какие сроки уведомления?"
        );

        QueryChunk identifierChunk = chunks.stream()
                .filter(chunk -> !chunk.identifiers().isEmpty())
                .findFirst()
                .orElseThrow();

        RetrievalPlan plan = planner.plan(List.of(identifierChunk));

        RetrievalStep identifier = plan.steps().stream()
                .filter(step -> step.type() == RetrievalType.IDENTIFIER)
                .findFirst()
                .orElseThrow();
        RetrievalStep vector = plan.steps().stream()
                .filter(step -> step.type() == RetrievalType.VECTOR)
                .findFirst()
                .orElseThrow();
        RetrievalStep lexical = plan.steps().stream()
                .filter(step -> step.type() == RetrievalType.LEXICAL)
                .findFirst()
                .orElseThrow();
        RetrievalStep concept = plan.steps().stream()
                .filter(step -> step.type() == RetrievalType.CONCEPT)
                .findFirst()
                .orElseThrow();
        RetrievalStep reference = plan.steps().stream()
                .filter(step -> step.type() == RetrievalType.REFERENCE)
                .findFirst()
                .orElseThrow();

        assertThat(vector.dependsOn()).containsExactly(identifier.id());
        assertThat(lexical.dependsOn()).containsExactly(identifier.id());
        assertThat(concept.dependsOn()).containsExactly(identifier.id());
        assertThat(reference.dependsOn())
                .containsExactly(vector.id(), lexical.id(), concept.id());
    }

    private QueryChunker chunker() {
        IdentifierNormalizer normalizer = new IdentifierNormalizer();
        IdentifierExtractor extractor = new IdentifierExtractor(List.of(
                new BusinessIdentifierParsers.ContractNumberParser(normalizer),
                new BusinessIdentifierParsers.OrderNumberParser(normalizer)
        ));

        return new QueryChunker(
                new TextNormalizer(),
                extractor,
                new QueryLanguageDetector()
        );
    }
}
