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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveGraphOnlineExpansion {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdaptiveGraphOnlineExpansion.class);

    private final AdaptiveGraphProperties properties;
    private final PublishedSearchProjectionReader projectionReader;
    private final AkmaiMetrics metrics;

    public AdaptiveGraphOnlineExpansion(
            AdaptiveGraphProperties properties,
            PublishedSearchProjectionReader projectionReader,
            AkmaiMetrics metrics
    ) {
        this.properties = properties;
        this.projectionReader = projectionReader;
        this.metrics = metrics;
    }

    public List<RetrievalHit> expand(
            List<RetrievalHit> existingCandidates,
            AdaptiveGraphShadowExpansion.ShadowExpansionReport report,
            Set<Long> allowedAccessLevels
    ) {
        if (!properties.expansionEnabled()) {
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

    private List<RetrievalHit> expandInternal(
            List<RetrievalHit> existingCandidates,
            AdaptiveGraphShadowExpansion.ShadowExpansionReport report,
            Set<Long> allowedAccessLevels
    ) {
        LinkedHashSet<NodeKey> existing = new LinkedHashSet<>();
        existingCandidates.stream()
                .filter(RetrievalHit::hasRoutingIdentity)
                .forEach(hit -> existing.add(NodeKey.of(hit)));

        List<RetrievalHit> admitted = new ArrayList<>();
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

            SearchProjection projection = projectionReader
                    .findByDocumentGenerationAndChunkIds(
                            node.documentId(),
                            node.generation(),
                            List.of(node.chunkId()),
                            Set.of(node.accessLevel())
                    )
                    .stream()
                    .filter(value ->
                            value.accessLevel() == node.accessLevel()
                                    && value.generation() == node.generation()
                                    && node.documentId().equals(
                                            value.documentId()
                                    )
                                    && node.chunkId().equals(value.chunkId())
                    )
                    .findFirst()
                    .orElse(null);

            if (projection == null) {
                lifecycleRejected++;
                continue;
            }

            admitted.add(toHit(projection, candidate));
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
    }
}
