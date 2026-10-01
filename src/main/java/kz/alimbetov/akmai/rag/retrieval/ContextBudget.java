package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import org.springframework.stereotype.Component;

@Component
public class ContextBudget {

    private final TokenEstimator tokenEstimator;
    private final RetrievalProperties properties;

    public ContextBudget(TokenEstimator tokenEstimator, RetrievalProperties properties) {
        this.tokenEstimator = tokenEstimator;
        this.properties = properties;
    }

    public List<RetrievalHit> apply(List<RetrievalHit> hits) {
        List<RetrievalHit> selected = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();
        int tokens = 0;

        for (RetrievalHit hit : hits) {
            if (selected.size() >= properties.contextMaxChunks()) {
                break;
            }

            String documentId = hit.documentId() == null ? "" : hit.documentId();
            if (perDocument.getOrDefault(documentId, 0) >= properties.contextMaxChunksPerDocument()) {
                continue;
            }

            int hitTokens = tokenEstimator.estimate(hit.text());
            if (tokens + hitTokens > properties.contextMaxTokens()) {
                continue;
            }

            selected.add(hit);
            tokens += hitTokens;
            perDocument.merge(documentId, 1, Integer::sum);
        }

        return List.copyOf(selected);
    }
}
