package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import org.springframework.stereotype.Component;

@Component
public class ContextBudget {

    private static final int MAX_TOKENS = 6000;
    private static final int MAX_CHUNKS = 12;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 4;

    private final TokenEstimator tokenEstimator;

    public ContextBudget(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    public List<RetrievalHit> apply(List<RetrievalHit> hits) {
        List<RetrievalHit> selected = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();
        int tokens = 0;

        for (RetrievalHit hit : hits) {
            if (selected.size() >= MAX_CHUNKS) {
                break;
            }

            String documentId = hit.documentId() == null ? "" : hit.documentId();
            if (perDocument.getOrDefault(documentId, 0) >= MAX_CHUNKS_PER_DOCUMENT) {
                continue;
            }

            int hitTokens = tokenEstimator.estimate(hit.text());
            if (!selected.isEmpty() && tokens + hitTokens > MAX_TOKENS) {
                continue;
            }

            selected.add(hit);
            tokens += hitTokens;
            perDocument.merge(documentId, 1, Integer::sum);
        }

        return List.copyOf(selected);
    }
}
