package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Preserves ranking order while preventing one document section from consuming
 * the whole context budget. Exact-authority hits are never removed.
 */
@Component
public class ContextDiversityFilter {

    private final RetrievalIntelligenceProperties properties;

    public ContextDiversityFilter(
            RetrievalIntelligenceProperties properties
    ) {
        this.properties = properties;
    }

    public List<RetrievalHit> apply(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        if (!properties.sectionDiversityEnabled()) {
            return List.copyOf(hits);
        }

        Map<String, Integer> perSection = new HashMap<>();
        List<RetrievalHit> selected = new ArrayList<>(hits.size());
        for (RetrievalHit hit : hits) {
            if (hit == null) {
                continue;
            }
            if (authorityTier(hit) == 0) {
                selected.add(hit);
                continue;
            }

            String sectionKey = sectionKey(hit);
            if (sectionKey == null) {
                selected.add(hit);
                continue;
            }
            if (perSection.getOrDefault(sectionKey, 0)
                    >= properties.maxChunksPerSection()) {
                continue;
            }
            selected.add(hit);
            perSection.merge(sectionKey, 1, Integer::sum);
        }
        return List.copyOf(selected);
    }

    private int authorityTier(RetrievalHit hit) {
        Object value = hit.metadata().get("authorityTier");
        return value instanceof Number number
                ? Math.max(0, number.intValue())
                : 2;
    }

    private String sectionKey(RetrievalHit hit) {
        Object raw = hit.metadata().get("sectionPath");
        if (!(raw instanceof String section) || section.isBlank()) {
            return null;
        }
        return String.valueOf(hit.documentId()) + "\u0000" + section.trim();
    }
}
