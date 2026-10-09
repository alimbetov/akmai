package kz.alimbetov.akmai.knowledge.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveGraphCompetitiveAdmission {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdaptiveGraphCompetitiveAdmission.class);

    private final AdaptiveGraphCompetitionProperties properties;
    private final AkmaiMetrics metrics;
    private final AppParameterService appParameterService;

    public AdaptiveGraphCompetitiveAdmission(
            AdaptiveGraphCompetitionProperties properties,
            AkmaiMetrics metrics
    ) {
        this(properties, metrics, null);
    }

    @Autowired
    public AdaptiveGraphCompetitiveAdmission(
            AdaptiveGraphCompetitionProperties properties,
            AkmaiMetrics metrics,
            AppParameterService appParameterService
    ) {
        this.properties = properties;
        this.metrics = metrics;
        this.appParameterService = appParameterService;
    }

    public List<RetrievalHit> admit(List<RetrievalHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!runtimeEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_COMPETITION_ENABLED,
                properties.enabled()
        )) {
            return List.copyOf(candidates);
        }

        try {
            return admitInternal(candidates);
        } catch (RuntimeException exception) {
            metrics.adaptiveGraphExpansion("competition_failed", 1);
            LOGGER.warn(
                    "adaptive_graph_competition event=failed errorType={}",
                    exception.getClass().getSimpleName()
            );
            return List.copyOf(candidates);
        }
    }

    private boolean runtimeEnabled(
            AppParameterKey key,
            boolean staticEnabled
    ) {
        if (!staticEnabled) {
            return false;
        }
        return appParameterService == null
                || appParameterService.isEnabled(key);
    }

    private List<RetrievalHit> admitInternal(List<RetrievalHit> candidates) {
        List<RetrievalHit> base = candidates.stream()
                .filter(hit -> hit.type() != RetrievalType.GRAPH)
                .toList();
        List<RetrievalHit> graph = candidates.stream()
                .filter(hit -> hit.type() == RetrievalType.GRAPH)
                .toList();

        int highAuthorityPrefix = 0;
        for (int index = 0; index < base.size(); index++) {
            if (authorityTier(base.get(index)) <= 1) {
                highAuthorityPrefix = index + 1;
            }
        }
        int protectedPrefix = Math.min(
                Math.max(
                        properties.protectedBasePrefix(),
                        highAuthorityPrefix
                ),
                base.size()
        );
        int promotionCapacity = Math.min(
                properties.maxPromotions(),
                Math.max(0, base.size() - protectedPrefix)
        );
        if (promotionCapacity == 0 || graph.isEmpty()) {
            return List.copyOf(candidates);
        }

        List<RetrievalHit> promoted = graph.stream()
                .filter(this::eligible)
                .sorted(
                        Comparator.comparingDouble(this::graphScore)
                                .reversed()
                                .thenComparing(
                                        Comparator.comparingInt(
                                                this::contributingEdges
                                        ).reversed()
                                )
                                .thenComparing(
                                        RetrievalHit::documentId,
                                        Comparator.nullsLast(
                                                Comparator.naturalOrder()
                                        )
                                )
                                .thenComparing(
                                        RetrievalHit::chunkId,
                                        Comparator.nullsLast(
                                                Comparator.naturalOrder()
                                        )
                                )
                )
                .limit(promotionCapacity)
                .toList();

        if (promoted.isEmpty()) {
            return List.copyOf(candidates);
        }

        ArrayList<RetrievalHit> result =
                new ArrayList<>(candidates.size());
        result.addAll(base.subList(0, protectedPrefix));

        int baseIndex = protectedPrefix;
        for (RetrievalHit graphHit : promoted) {
            result.add(graphHit);
            if (baseIndex < base.size()) {
                result.add(base.get(baseIndex++));
            }
        }
        while (baseIndex < base.size()) {
            result.add(base.get(baseIndex++));
        }
        graph.stream()
                .filter(hit -> !promoted.contains(hit))
                .forEach(result::add);

        metrics.adaptiveGraphExpansion(
                "competition_promoted",
                promoted.size()
        );
        return List.copyOf(result);
    }

    private int authorityTier(RetrievalHit hit) {
        Object value = hit.metadata().get("authorityTier");
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (hit.type() == RetrievalType.IDENTIFIER
                || hit.type() == RetrievalType.REFERENCE) {
            return 0;
        }
        return 2;
    }

    private boolean eligible(RetrievalHit hit) {
        return "HOT".equals(hit.metadata().get("adaptiveGraphBand"))
                && graphScore(hit) >= properties.minGraphScore();
    }

    private double graphScore(RetrievalHit hit) {
        Object value = hit.metadata().get("adaptiveGraphScore");
        if (value instanceof Number number
                && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        return Double.NEGATIVE_INFINITY;
    }

    private int contributingEdges(RetrievalHit hit) {
        Object value = hit.metadata().get(
                "adaptiveGraphContributingEdges"
        );
        return value instanceof Number number
                ? Math.max(0, number.intValue())
                : 0;
    }
}
