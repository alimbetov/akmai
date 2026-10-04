package kz.alimbetov.akmai.knowledge.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
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
public class AdaptiveGraphOnlineExpansion {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdaptiveGraphOnlineExpansion.class);

    private final AdaptiveGraphProperties properties;
    private final PublishedSearchProjectionReader projectionReader;
    private final AkmaiMetrics metrics;
    private final AppParameterService appParameterService;

    public AdaptiveGraphOnlineExpansion(
            AdaptiveGraphProperties properties,
            PublishedSearchProjectionReader projectionReader,
            AkmaiMetrics metrics
    ) {
        this(properties, projectionReader, metrics, null);
    }

    @Autowired
    public AdaptiveGraphOnlineExpansion(
            AdaptiveGraphProperties properties,
            PublishedSearchProjectionReader projectionReader,
            AkmaiMetrics metrics,
            AppParameterService appParameterService
    ) {
        this.properties = properties;
        this.projectionReader = projectionReader;
        this.metrics = metrics;
        this.appParameterService = appParameterService;
    }

    public List<RetrievalHit> expand(
            List<RetrievalHit> existingCandidates,
            AdaptiveGraphShadowExpansion.ShadowExpansionReport report,
            Set<Long> allowedAccessLevels
    ) {
        if (!runtimeEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_EXPANSION_ENABLED,
                properties.expansionEnabled()
        )) {
            return copy(existingCandidates);
        }
        if (existingCandidates == null || existingCandidates.isEmpty()) {
            return List.of();
        }
        if (report == null || report.failed() || report.candidates().isEmpty()) {
            return List.copyOf(existingCandidates);
        }

        try {
            requireAccessLevels(allowedAccessLevels);
            return expandInternal(
                    existingCandidates,
                    report,
                    allowedAccessLevels
            );
        } catch (RuntimeException exception) {
            metrics.adaptiveGraphExpansion("online_failed", 1);
            LOGGER.warn(
                    "adaptive_graph_online_expansion event=failed errorType={}",
                    exception.getClass().getSimpleName()
            );
            return List.copyOf(existingCandidates);
        }
    }

    private boolean runtimeEnabled(
            AppParameterKey key,
            boolean fallback
    ) {
        return appParameterService == null
                ? fallback
                : appParameterService.isEnabled(key);
    }

    private List<RetrievalHit> expandInternal(
            List<RetrievalHit> existingCandidates,
            AdaptiveGraphShadowExpansion.ShadowExpansionReport report,
            Set<Long> allowedAccessLevels
    ) {
        LinkedHashSet<NodeKey> existing = new LinkedHashSet<>();
        existingCandidates.stream()
                .filter(RetrievalHit::hasRoutingIdentity)
                .forEach(hit -> existing.add(NodeKey.of(hit)));

        LinkedHashMap<
                NodeKey,
                AdaptiveGraphShadowExpansion.ShadowCandidate
        > pending = new LinkedHashMap<>();
        int lifecycleRejected = 0;
        int aclRejected = 0;
        int duplicateRejected = 0;

        for (AdaptiveGraphShadowExpansion.ShadowCandidate candidate
                : report.candidates()) {
            ChunkGraphNode node = candidate.node();
            if (!allowedAccessLevels.contains(node.accessLevel())) {
                aclRejected++;
                continue;
            }

            NodeKey key = NodeKey.of(node);
            if (!existing.add(key)) {
                duplicateRejected++;
                continue;
            }
            pending.put(key, candidate);
        }

        List<RetrievalHit> admitted = new ArrayList<>();
        if (!pending.isEmpty()) {
            List<PublishedSearchProjectionReader.ProjectionKey> keys =
                    pending.keySet().stream()
                            .map(NodeKey::toProjectionKey)
                            .toList();
            List<SearchProjection> published =
                    projectionReader.findPublishedByKeys(
                            keys,
                            allowedAccessLevels
                    );

            LinkedHashMap<NodeKey, SearchProjection> projections =
                    new LinkedHashMap<>();
            published.forEach(projection -> {
                NodeKey key = NodeKey.of(projection);
                if (allowedAccessLevels.contains(key.accessLevel())
                        && pending.containsKey(key)) {
                    projections.putIfAbsent(key, projection);
                }
            });

            pending.forEach((key, candidate) -> {
                SearchProjection projection = projections.get(key);
                if (projection == null) {
                    return;
                }
                admitted.add(toHit(projection, candidate));
            });
            lifecycleRejected = pending.size() - admitted.size();
        }

        metrics.adaptiveGraphExpansion("online_added", admitted.size());
        metrics.adaptiveGraphExpansion(
                "online_lifecycle_rejected",
                lifecycleRejected
        );
        metrics.adaptiveGraphExpansion("online_acl_rejected", aclRejected);
        metrics.adaptiveGraphExpansion(
                "online_duplicate_rejected",
                duplicateRejected
        );

        if (admitted.isEmpty()) {
            return List.copyOf(existingCandidates);
        }

        ArrayList<RetrievalHit> result = new ArrayList<>(
                existingCandidates.size() + admitted.size()
        );
        result.addAll(existingCandidates);
        result.addAll(admitted);
        return List.copyOf(result);
    }

    private RetrievalHit toHit(
            SearchProjection projection,
            AdaptiveGraphShadowExpansion.ShadowCandidate candidate
    ) {
        Map<String, Object> metadata =
                new LinkedHashMap<>(projection.metadata());
        metadata.put("language", projection.language());
        metadata.put(
                "domain",
                projection.domain() == null
                        ? ""
                        : projection.domain().name()
        );
        metadata.put(
                "sectionPath",
                projection.sectionPath() == null
                        ? ""
                        : projection.sectionPath()
        );
        metadata.put("chunkIndex", projection.chunkIndex());
        metadata.put("generation", projection.generation());
        metadata.put("expansion", "adaptive_graph");
        metadata.put("authorityTier", 4);
        metadata.put("adaptiveGraphScore", candidate.score());
        metadata.put(
                "adaptiveGraphBand",
                candidate.strongestBand().name()
        );
        metadata.put(
                "adaptiveGraphContributingEdges",
                candidate.contributingEdges()
        );
        metadata.put("adaptiveGraphVersion", properties.graphVersion());

        return new RetrievalHit(
                RetrievalType.GRAPH,
                projection.accessLevel(),
                projection.documentId(),
                projection.generation(),
                projection.chunkId(),
                projection.text(),
                metadata,
                List.of(),
                candidate.score()
        );
    }

    private List<RetrievalHit> copy(List<RetrievalHit> hits) {
        return hits == null ? List.of() : List.copyOf(hits);
    }

    private void requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedAccessLevels must not be empty"
            );
        }
        if (accessLevels.stream()
                .anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "allowedAccessLevels must contain positive values"
            );
        }
    }

    private record NodeKey(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {

        static NodeKey of(RetrievalHit hit) {
            return new NodeKey(
                    hit.accessLevel(),
                    hit.documentId(),
                    hit.generation(),
                    hit.chunkId()
            );
        }

        static NodeKey of(ChunkGraphNode node) {
            return new NodeKey(
                    node.accessLevel(),
                    node.documentId(),
                    node.generation(),
                    node.chunkId()
            );
        }

        static NodeKey of(SearchProjection projection) {
            return new NodeKey(
                    projection.accessLevel(),
                    projection.documentId(),
                    projection.generation(),
                    projection.chunkId()
            );
        }

        PublishedSearchProjectionReader.ProjectionKey toProjectionKey() {
            return new PublishedSearchProjectionReader.ProjectionKey(
                    accessLevel,
                    documentId,
                    generation,
                    chunkId
            );
        }
    }
}
