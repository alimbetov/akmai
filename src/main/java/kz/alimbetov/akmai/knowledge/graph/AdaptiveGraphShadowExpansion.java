package kz.alimbetov.akmai.knowledge.graph;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveGraphShadowExpansion {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdaptiveGraphShadowExpansion.class);

    private final AdaptiveGraphProperties properties;
    private final AdaptiveGraphLookupReader graphRepository;
    private final PublishedSearchProjectionReader projectionReader;
    private final AkmaiMetrics metrics;

    public AdaptiveGraphShadowExpansion(
            AdaptiveGraphProperties properties,
            AdaptiveGraphLookupReader graphRepository,
            PublishedSearchProjectionReader projectionReader,
            AkmaiMetrics metrics
    ) {
        this.properties = properties;
        this.graphRepository = graphRepository;
        this.projectionReader = projectionReader;
        this.metrics = metrics;
    }

    public ShadowExpansionReport observe(
            List<RetrievalHit> rankedSeeds,
            List<RetrievalHit> existingCandidates,
            Set<Long> allowedAccessLevels
    ) {
        if (!properties.shadowExpansionEnabled()
                && !properties.expansionEnabled()) {
            return ShadowExpansionReport.disabled();
        }

        try {
            return observeInternal(
                    rankedSeeds,
                    existingCandidates,
                    allowedAccessLevels
            );
        } catch (RuntimeException exception) {
            metrics.adaptiveGraphExpansion("shadow_failed", 1);
            LOGGER.warn(
                    "adaptive_graph_shadow_expansion event=failed errorType={}",
                    exception.getClass().getSimpleName()
            );
            return ShadowExpansionReport.failure();
        }
    }

    private ShadowExpansionReport observeInternal(
            List<RetrievalHit> rankedSeeds,
            List<RetrievalHit> existingCandidates,
            Set<Long> allowedAccessLevels
    ) {
        requireAccessLevels(allowedAccessLevels);
        if (rankedSeeds == null || rankedSeeds.isEmpty()) {
            return ShadowExpansionReport.empty();
        }

        AdaptiveGraphProperties.ShadowExpansion config =
                properties.shadowExpansion();
        List<Seed> seeds = selectSeeds(
                rankedSeeds,
                allowedAccessLevels,
                config
        );
        metrics.adaptiveGraphShadowSeeds(seeds.size());
        if (seeds.isEmpty()) {
            return ShadowExpansionReport.empty();
        }

        Set<NodeKey> existing = existingKeys(existingCandidates);
        LinkedHashMap<NodeKey, CandidateAccumulator> accumulated =
                new LinkedHashMap<>();

        int graphEdgesRead = 0;
        int duplicates = 0;
        int aclRejected = 0;

        for (Seed seed : seeds) {
            LookupResult hot = lookup(
                    seed,
                    AssociationBand.HOT,
                    config.minHotWeight(),
                    config.hotPerSeed(),
                    config.hotBandFactor(),
                    allowedAccessLevels
            );
            LookupResult warm = config.warmPerSeed() == 0
                    ? LookupResult.empty()
                    : lookup(
                            seed,
                            AssociationBand.WARM,
                            config.minWarmWeight(),
                            config.warmPerSeed(),
                            config.warmBandFactor(),
                            allowedAccessLevels
                    );

            for (ScoredEdge edge : concat(hot.edges(), warm.edges())) {
                graphEdgesRead++;
                NodeKey targetKey = NodeKey.of(edge.association().target());
                if (!allowedAccessLevels.contains(targetKey.accessLevel())) {
                    aclRejected++;
                    continue;
                }
                if (existing.contains(targetKey)) {
                    duplicates++;
                    continue;
                }
                accumulated.computeIfAbsent(
                                targetKey,
                                ignored -> new CandidateAccumulator(
                                        edge.association().target()
                                )
                        )
                        .add(
                                edge.contribution(),
                                edge.association().band()
                        );
            }
        }

        ValidationResult validation = validatePublished(
                accumulated,
                allowedAccessLevels
        );

        List<ShadowCandidate> ordered = validation.valid().stream()
                .sorted(
                        Comparator.comparingDouble(
                                        ShadowCandidate::score
                                )
                                .reversed()
                                .thenComparing(candidate ->
                                        candidate.node().documentId())
                                .thenComparingLong(candidate ->
                                        candidate.node().generation())
                                .thenComparing(candidate ->
                                        candidate.node().chunkId())
                )
                .toList();

        int budgetRejected = Math.max(
                0,
                ordered.size() - config.maxCandidates()
        );
        List<ShadowCandidate> emitted = ordered.stream()
                .limit(config.maxCandidates())
                .toList();

        metrics.adaptiveGraphExpansion(
                "shadow_would_add",
                emitted.size()
        );
        metrics.adaptiveGraphExpansion("shadow_duplicate", duplicates);
        metrics.adaptiveGraphExpansion(
                "shadow_lifecycle_rejected",
                validation.rejected()
        );
        metrics.adaptiveGraphExpansion(
                "shadow_acl_rejected",
                aclRejected
        );
        metrics.adaptiveGraphExpansion(
                "shadow_budget_rejected",
                budgetRejected
        );
        emitted.forEach(candidate ->
                metrics.adaptiveGraphShadowCandidateScore(
                        candidate.score()
                )
        );

        return new ShadowExpansionReport(
                seeds.size(),
                graphEdgesRead,
                duplicates,
                validation.rejected(),
                aclRejected,
                budgetRejected,
                emitted,
                false
        );
    }

    private List<Seed> selectSeeds(
            List<RetrievalHit> ranked,
            Set<Long> allowedAccessLevels,
            AdaptiveGraphProperties.ShadowExpansion config
    ) {
        double maxFused = ranked.stream()
                .mapToDouble(RetrievalHit::fusedScore)
                .filter(Double::isFinite)
                .filter(value -> value > 0)
                .max()
                .orElse(0.0);

        List<Seed> result = new ArrayList<>();
        for (RetrievalHit hit : ranked) {
            if (result.size() >= config.maxSeeds()) {
                break;
            }
            if (!hit.hasRoutingIdentity()
                    || !allowedAccessLevels.contains(hit.accessLevel())) {
                continue;
            }
            double strength = seedStrength(hit, maxFused);
            if (strength < config.minSeedStrength()) {
                continue;
            }
            result.add(new Seed(
                    new ChunkGraphNode(
                            hit.accessLevel(),
                            hit.documentId(),
                            hit.generation(),
                            hit.chunkId()
                    ),
                    strength
            ));
        }
        return List.copyOf(result);
    }

    private double seedStrength(RetrievalHit hit, double maxFused) {
        if (authorityTier(hit) == 0) {
            return 1.0;
        }

        double rerank = finiteNumber(
                hit.metadata().get("rerankScore")
        );
        double fused = maxFused <= 0
                ? 0.0
                : hit.fusedScore() / maxFused;
        return clamp(Math.max(rerank, fused));
    }

    private LookupResult lookup(
            Seed seed,
            AssociationBand band,
            double minimumWeight,
            int limit,
            double bandFactor,
            Set<Long> allowedAccessLevels
    ) {
        Instant started = Instant.now();
        List<ChunkAssociation> associations = graphRepository.findRelated(
                allowedAccessLevels,
                seed.node(),
                properties.graphVersion(),
                Set.of(band),
                minimumWeight,
                limit
        );
        metrics.adaptiveGraphLookup(
                band.name(),
                Duration.between(started, Instant.now()),
                associations.size()
        );

        return new LookupResult(
                associations.stream()
                        .map(association -> new ScoredEdge(
                                association,
                                clamp(
                                        seed.strength()
                                                * association.weight()
                                                * bandFactor
                                )
                        ))
                        .toList()
        );
    }

    private ValidationResult validatePublished(
            Map<NodeKey, CandidateAccumulator> accumulated,
            Set<Long> allowedAccessLevels
    ) {
        LinkedHashMap<DocumentGeneration, List<NodeKey>> grouped =
                new LinkedHashMap<>();
        accumulated.keySet().forEach(key ->
                grouped.computeIfAbsent(
                        new DocumentGeneration(
                                key.accessLevel(),
                                key.documentId(),
                                key.generation()
                        ),
                        ignored -> new ArrayList<>()
                ).add(key)
        );

        List<ShadowCandidate> valid = new ArrayList<>();
        int rejected = 0;

        for (Map.Entry<DocumentGeneration, List<NodeKey>> entry
                : grouped.entrySet()) {
            DocumentGeneration group = entry.getKey();
            Set<String> requested = entry.getValue().stream()
                    .map(NodeKey::chunkId)
                    .collect(
                            java.util.stream.Collectors.toCollection(
                                    LinkedHashSet::new
                            )
                    );

            List<SearchProjection> projections =
                    projectionReader.findByDocumentGenerationAndChunkIds(
                            group.documentId(),
                            group.generation(),
                            List.copyOf(requested),
                            Set.of(group.accessLevel())
                    );
            Set<String> publishedChunkIds = projections.stream()
                    .filter(projection ->
                            projection.accessLevel() == group.accessLevel()
                                    && allowedAccessLevels.contains(
                                            projection.accessLevel()
                                    )
                    )
                    .map(SearchProjection::chunkId)
                    .collect(java.util.stream.Collectors.toSet());

            for (NodeKey key : entry.getValue()) {
                if (!publishedChunkIds.contains(key.chunkId())) {
                    rejected++;
                    continue;
                }
                CandidateAccumulator candidate = accumulated.get(key);
                valid.add(new ShadowCandidate(
                        candidate.node,
                        candidate.score,
                        candidate.strongestBand,
                        candidate.contributingEdges
                ));
            }
        }

        return new ValidationResult(List.copyOf(valid), rejected);
    }

    private Set<NodeKey> existingKeys(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<NodeKey> result = new LinkedHashSet<>();
        hits.stream()
                .filter(RetrievalHit::hasRoutingIdentity)
                .forEach(hit -> result.add(new NodeKey(
                        hit.accessLevel(),
                        hit.documentId(),
                        hit.generation(),
                        hit.chunkId()
                )));
        return Set.copyOf(result);
    }

    private List<ScoredEdge> concat(
            List<ScoredEdge> first,
            List<ScoredEdge> second
    ) {
        ArrayList<ScoredEdge> result = new ArrayList<>(
                first.size() + second.size()
        );
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private int authorityTier(RetrievalHit hit) {
        Object value = hit.metadata().get("authorityTier");
        return value instanceof Number number
                ? Math.max(0, number.intValue())
                : 2;
    }

    private double finiteNumber(Object value) {
        if (value instanceof Number number
                && Double.isFinite(number.doubleValue())) {
            return clamp(number.doubleValue());
        }
        return 0.0;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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

    private record Seed(
            ChunkGraphNode node,
            double strength
    ) {
    }

    private record LookupResult(List<ScoredEdge> edges) {

        static LookupResult empty() {
            return new LookupResult(List.of());
        }
    }

    private record ScoredEdge(
            ChunkAssociation association,
            double contribution
    ) {
    }

    private record DocumentGeneration(
            long accessLevel,
            String documentId,
            long generation
    ) {
    }

    private record ValidationResult(
            List<ShadowCandidate> valid,
            int rejected
    ) {
    }

    private record NodeKey(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {

        static NodeKey of(ChunkGraphNode node) {
            return new NodeKey(
                    node.accessLevel(),
                    node.documentId(),
                    node.generation(),
                    node.chunkId()
            );
        }
    }

    private static final class CandidateAccumulator {

        private final ChunkGraphNode node;
        private double score;
        private AssociationBand strongestBand = AssociationBand.WARM;
        private int contributingEdges;

        private CandidateAccumulator(ChunkGraphNode node) {
            this.node = node;
        }

        private void add(double contribution, AssociationBand band) {
            score = 1.0 - ((1.0 - score) * (1.0 - contribution));
            score = Math.max(0.0, Math.min(1.0, score));
            if (band == AssociationBand.HOT) {
                strongestBand = AssociationBand.HOT;
            }
            contributingEdges++;
        }
    }

    public record ShadowCandidate(
            ChunkGraphNode node,
            double score,
            AssociationBand strongestBand,
            int contributingEdges
    ) {
    }

    public record ShadowExpansionReport(
            int seedCount,
            int graphEdgesRead,
            int duplicates,
            int lifecycleRejected,
            int aclRejected,
            int budgetRejected,
            List<ShadowCandidate> candidates,
            boolean failed
    ) {
        public ShadowExpansionReport {
            candidates = candidates == null
                    ? List.of()
                    : List.copyOf(candidates);
        }

        static ShadowExpansionReport disabled() {
            return empty();
        }

        static ShadowExpansionReport empty() {
            return new ShadowExpansionReport(
                    0, 0, 0, 0, 0, 0, List.of(), false
            );
        }

        static ShadowExpansionReport failure() {
            return new ShadowExpansionReport(
                    0, 0, 0, 0, 0, 0, List.of(), true
            );
        }
    }
}
