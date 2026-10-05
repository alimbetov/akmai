package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.springframework.stereotype.Component;

@Component
public class ResultFusion {

    private final RetrievalProperties properties;
    private final PublishedSearchProjectionReader projectionRepository;

    public ResultFusion(
            RetrievalProperties properties,
            PublishedSearchProjectionReader projectionRepository
    ) {
        this.properties = properties;
        this.projectionRepository = projectionRepository;
    }

    public List<RetrievalHit> fuse(
            List<RetrievalHit> hits,
            Set<Long> accessLevels
    ) {
        Set<Long> scope = requireAccessLevels(accessLevels);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<RoutedHit> routed = hits.stream()
                .map(hit -> routed(hit, scope))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (routed.isEmpty()) {
            return List.of();
        }

        Map<CanonicalKey, SearchProjection> canonical =
                canonicalProjections(routed, scope);
        if (canonical.isEmpty()) {
            return List.of();
        }

        Map<CanonicalKey, Accumulator> accumulated = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();

        for (RoutedHit routedHit : routed) {
            RetrievalHit hit = routedHit.hit();
            SearchProjection projection = canonical.get(routedHit.key());
            if (projection == null) {
                continue;
            }

            int rank = ranks.merge(rankKey(hit), 1, Integer::sum);
            RetrievalEvidence evidence = new RetrievalEvidence(
                    hit.type(),
                    rank,
                    rawScore(hit)
            );
            accumulated.computeIfAbsent(
                            routedHit.key(),
                            ignored -> new Accumulator(
                                    hit,
                                    routedHit.key().accessLevel(),
                                    routedHit.key().generation()
                            )
                    )
                    .add(evidence, authorityTier(hit));
        }

        return accumulated.entrySet().stream()
                .map(entry -> entry.getValue().toHit(
                        canonical.get(entry.getKey())
                ))
                .sorted(
                        Comparator.comparingInt(this::authorityTierFromCanonical)
                                .thenComparing(
                                        Comparator.comparingDouble(
                                                RetrievalHit::fusedScore
                                        ).reversed()
                                )
                                .thenComparing(RetrievalHit::documentId)
                                .thenComparing(RetrievalHit::chunkId)
                )
                .toList();
    }

    private RoutedHit routed(
            RetrievalHit hit,
            Set<Long> allowed
    ) {
        if (hit == null
                || !hit.hasRoutingIdentity()
                || !allowed.contains(hit.accessLevel())) {
            return null;
        }
        return new RoutedHit(
                hit,
                new CanonicalKey(
                        hit.accessLevel(),
                        hit.documentId(),
                        hit.generation(),
                        hit.chunkId()
                )
        );
    }

    private Map<CanonicalKey, SearchProjection> canonicalProjections(
            List<RoutedHit> routed,
            Set<Long> accessLevels
    ) {
        List<PublishedSearchProjectionReader.ProjectionKey> keys =
                routed.stream()
                        .map(RoutedHit::key)
                        .distinct()
                        .map(key ->
                                new PublishedSearchProjectionReader.ProjectionKey(
                                        key.accessLevel(),
                                        key.documentId(),
                                        key.generation(),
                                        key.chunkId()
                                )
                        )
                        .toList();

        LinkedHashMap<CanonicalKey, SearchProjection> result =
                new LinkedHashMap<>();
        projectionRepository.findPublishedByKeys(keys, accessLevels)
                .forEach(projection -> {
                    if (projection.accessLevel() <= 0
                            || projection.generation() <= 0) {
                        return;
                    }
                    result.put(
                            new CanonicalKey(
                                    projection.accessLevel(),
                                    projection.documentId(),
                                    projection.generation(),
                                    projection.chunkId()
                            ),
                            projection
                    );
                });
        return result;
    }

    private String rankKey(RetrievalHit hit) {
        Object queryChunkId = hit.metadata().get("queryChunkId");
        return String.valueOf(queryChunkId) + "|" + hit.type();
    }

    private Double rawScore(RetrievalHit hit) {
        Object score = hit.metadata().get("score");
        return score instanceof Number number
                && Double.isFinite(number.doubleValue())
                ? number.doubleValue()
                : null;
    }

    private int authorityTier(RetrievalHit hit) {
        return switch (hit.type()) {
            case IDENTIFIER -> 0;
            case REFERENCE -> exactReference(hit) ? 0 : 3;
            case VECTOR, LEXICAL, CONCEPT -> 2;
            case GRAPH -> 4;
        };
    }

    private boolean exactReference(RetrievalHit hit) {
        return "EXACT_REFERENCE".equals(
                hit.metadata().get("authority")
        );
    }

    private int authorityTierFromCanonical(RetrievalHit hit) {
        Object explicit = hit.metadata().get("authorityTier");
        return explicit instanceof Number number
                ? Math.max(0, number.intValue())
                : authorityTier(hit);
    }

    private Set<Long> requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        java.util.TreeSet<Long> normalized = new java.util.TreeSet<>();
        for (Long value : accessLevels) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(
                        "accessLevels must contain positive values"
                );
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private final class Accumulator {

        private final RetrievalHit representative;
        private final long accessLevel;
        private final long generation;
        private final List<RetrievalEvidence> evidence = new ArrayList<>();
        private double fusedScore;
        private int authorityTier = Integer.MAX_VALUE;

        private Accumulator(
                RetrievalHit representative,
                long accessLevel,
                long generation
        ) {
            this.representative = representative;
            this.accessLevel = accessLevel;
            this.generation = generation;
        }

        private void add(RetrievalEvidence item, int tier) {
            evidence.add(item);
            authorityTier = Math.min(authorityTier, tier);
            fusedScore += 1.0 / (properties.rrfK() + item.rank());
        }

        private RetrievalHit toHit(SearchProjection canonical) {
            RetrievalHit source = canonicalHit(
                    canonical,
                    representative.metadata()
            );
            Map<String, Object> metadata =
                    new LinkedHashMap<>(source.metadata());
            metadata.put("authorityTier", authorityTier);
            return new RetrievalHit(
                    source.type(),
                    source.accessLevel(),
                    source.documentId(),
                    source.generation(),
                    source.chunkId(),
                    source.text(),
                    metadata,
                    evidence,
                    fusedScore
            );
        }

        private RetrievalHit canonicalHit(
                SearchProjection projection,
                Map<String, Object> evidenceMetadata
        ) {
            Map<String, Object> metadata =
                    new LinkedHashMap<>(projection.metadata());
            metadata.putAll(evidenceMetadata);
            metadata.put("generation", projection.generation());
            metadata.put("language", projection.language());
            metadata.put(
                    "sectionPath",
                    projection.sectionPath() == null
                            ? ""
                            : projection.sectionPath()
            );
            metadata.put("chunkIndex", projection.chunkIndex());
            return new RetrievalHit(
                    representative.type(),
                    projection.accessLevel(),
                    projection.documentId(),
                    projection.generation(),
                    projection.chunkId(),
                    projection.text(),
                    metadata
            );
        }
    }

    private record RoutedHit(
            RetrievalHit hit,
            CanonicalKey key
    ) {
    }

    private record CanonicalKey(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
    }
}
