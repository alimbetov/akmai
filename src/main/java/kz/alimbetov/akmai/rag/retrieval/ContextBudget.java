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
    private final ChatTokenBudgetService chatTokenBudgetService;

    public ContextBudget(
            TokenEstimator tokenEstimator,
            RetrievalProperties properties
    ) {
        this(
                tokenEstimator,
                properties,
                new ContextAssembler(new ObjectMapper()),
                null
        );
    }

    @Autowired
    public ContextBudget(
            TokenEstimator tokenEstimator,
            RetrievalProperties properties,
            ContextAssembler contextAssembler
    ) {
        this(tokenEstimator, properties, contextAssembler, null);
    }

    @Autowired
    public ContextBudget(
            TokenEstimator tokenEstimator,
            RetrievalProperties properties,
            ContextAssembler contextAssembler,
            ChatTokenBudgetService chatTokenBudgetService
    ) {
        this.tokenEstimator = tokenEstimator;
        this.properties = properties;
        this.contextAssembler = contextAssembler;
        this.chatTokenBudgetService = chatTokenBudgetService;
    }

    public List<RetrievalHit> apply(List<RetrievalHit> hits) {
        return apply(hits, "");
    }

    public List<RetrievalHit> apply(
            List<RetrievalHit> hits,
            String question
    ) {
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
            String serialized = contextAssembler.assemble(candidate);
            int serializedTokens = tokenEstimator.estimate(serialized);
            if (serializedTokens > properties.contextMaxTokens()) {
                continue;
            }
            if (chatTokenBudgetService != null
                    && !chatTokenBudgetService.fits(
                            question,
                            serialized
                    )) {
                continue;
            }

            selected.add(hit);
            perDocument.merge(documentId, 1, Integer::sum);
        }

        return List.copyOf(selected);
    }
}
