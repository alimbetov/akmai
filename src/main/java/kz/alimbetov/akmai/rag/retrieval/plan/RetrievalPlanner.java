package kz.alimbetov.akmai.rag.retrieval.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.springframework.stereotype.Component;

@Component
public class RetrievalPlanner {

    public RetrievalPlan plan(List<QueryChunk> chunks) {
        List<RetrievalStep> steps = new ArrayList<>();

        for (QueryChunk chunk : chunks) {
            if (chunk.identifiers().isEmpty()) {
                steps.add(step(chunk, RetrievalType.VECTOR, List.of()));
                steps.add(step(chunk, RetrievalType.LEXICAL, List.of()));
                continue;
            }

            RetrievalStep identifier = step(chunk, RetrievalType.IDENTIFIER, List.of());
            steps.add(identifier);

            if (!chunk.semanticText().isBlank()) {
                steps.add(step(chunk, RetrievalType.VECTOR, List.of(identifier.id())));
                steps.add(step(chunk, RetrievalType.LEXICAL, List.of(identifier.id())));
            }
        }

        return new RetrievalPlan(List.copyOf(steps));
    }

    private RetrievalStep step(
            QueryChunk chunk,
            RetrievalType type,
            List<String> dependencies
    ) {
        return new RetrievalStep(
                UUID.randomUUID().toString(),
                chunk,
                type,
                dependencies
        );
    }
}
