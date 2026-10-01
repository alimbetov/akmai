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
                RetrievalStep vector = step(chunk, RetrievalType.VECTOR, List.of());
                RetrievalStep lexical = step(chunk, RetrievalType.LEXICAL, List.of());
                steps.add(vector);
                steps.add(lexical);
                steps.add(step(
                        chunk,
                        RetrievalType.REFERENCE,
                        List.of(vector.id(), lexical.id())
                ));
                continue;
            }

            RetrievalStep identifier = step(chunk, RetrievalType.IDENTIFIER, List.of());
            steps.add(identifier);

            if (!chunk.semanticText().isBlank()) {
                RetrievalStep vector = step(
                        chunk,
                        RetrievalType.VECTOR,
                        List.of(identifier.id())
                );
                RetrievalStep lexical = step(
                        chunk,
                        RetrievalType.LEXICAL,
                        List.of(identifier.id())
                );
                steps.add(vector);
                steps.add(lexical);
                steps.add(step(
                        chunk,
                        RetrievalType.REFERENCE,
                        List.of(vector.id(), lexical.id())
                ));
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
