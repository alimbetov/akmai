package kz.alimbetov.akmai.knowledge.graph;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveGraphUtilityRecorder {

    private final AkmaiMetrics metrics;

    public AdaptiveGraphUtilityRecorder(AkmaiMetrics metrics) {
        this.metrics = metrics;
    }

    public void record(
            List<RetrievalHit> context,
            CitationValidator.CitationValidation validation
    ) {
        record(context, validation, true);
    }

    public void record(
            List<RetrievalHit> context,
            CitationValidator.CitationValidation validation,
            boolean acceptedAnswer
    ) {
        if (context == null || context.isEmpty()) {
            return;
        }

        Set<Integer> graphSources = new LinkedHashSet<>();
        for (int index = 0; index < context.size(); index++) {
            if (context.get(index).type() == RetrievalType.GRAPH) {
                graphSources.add(index + 1);
            }
        }
        if (graphSources.isEmpty()) {
            return;
        }

        metrics.adaptiveGraphUtilityRequest("selected");
        if (!acceptedAnswer
                || validation == null
                || validation.citedSources().isEmpty()) {
            return;
        }

        int citedGraph = (int) validation.citedSources().stream()
                .filter(source -> graphSources.contains(source.number()))
                .count();
        if (citedGraph > 0) {
            metrics.adaptiveGraphExpansion("online_cited", citedGraph);
            metrics.adaptiveGraphUtilityRequest("assisted");
        }
    }
}
