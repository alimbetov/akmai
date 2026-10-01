package kz.alimbetov.akmai.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ContextBudget {

    private final TokenEstimator tokenEstimator;
    private final RetrievalProperties properties;
    private final ContextAssembler contextAssembler;

    public ContextBudget(
            TokenEstimator tokenEstimator,
            RetrievalProperties properties
    ) {
        this(
                tokenEstimator,
                properties,
                new ContextAssembler(new ObjectMapper())
        );
    }

    @Autowired
    public ContextBudget(
            TokenEstimator tokenEstimator,
            RetrievalProperties properties,
            ContextAssembler contextAssembler
    ) {
        this.tokenEstimator = tokenEstimator;
        this.properties = properties;
        this.contextAssembler = contextAssembler;
    }

    public List<RetrievalHit> apply(List<RetrievalHit> hits) {
        List<RetrievalHit> selected = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();

        for (RetrievalHit hit : hits) {
            if (selected.size() >= properties.contextMaxChunks()) {
                break;
            }

            String documentId = hit.documentId() == null ? "" : hit.documentId();
            if (perDocument.getOrDefault(documentId, 0)
                    >= properties.contextMaxChunksPerDocument()) {
                continue;
            }

            List<RetrievalHit> candidate = new ArrayList<>(selected);
            candidate.add(hit);
            int serializedTokens = tokenEstimator.estimate(
                    contextAssembler.assemble(candidate)
            );
            if (serializedTokens > properties.contextMaxTokens()) {
                continue;
            }

            selected.add(hit);
            perDocument.merge(documentId, 1, Integer::sum);
        }

        return List.copyOf(selected);
    }
}
