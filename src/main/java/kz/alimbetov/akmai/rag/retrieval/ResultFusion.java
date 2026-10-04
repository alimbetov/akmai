package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
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
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        Map<CanonicalKey, Accumulator> accumulated = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();

        for (RetrievalHit hit : hits) {
            long generation = hit.generation();
            long accessLevel = effectiveAccessLevel(
                    hit,
                    accessLevels
            );
            if (accessLevel <= 0
                    || generation <= 0
                    || hit.documentId() == null
                    || hit.documentId().isBlank()
                    || hit.chunkId() == null
                    || hit.chunkId().isBlank()) {
                continue;
            }
            int rank = ranks.merge(rankKey(hit), 1, Integer::sum);
            RetrievalEvidence evidence = new RetrievalEvidence(
                    hit.type(),
                    rank,
                    rawScore(hit)
            );
            accumulated.computeIfAbsent(
                            key(hit, accessLevel, generation),
                            ignored -> new Accumulator(
                                    hit,
                                    accessLevel,
                                    generation
                            )
                    )
                    .add(evidence, authorityTier(hit));
        }

        if (accumulated.isEmpty()) {
            return List.of();
        }

        Map<CanonicalKey, SearchProjection> canonical = canonicalProjections(
                accumulated.values().stream().toList()
        );

        return accumulated.entrySet().stream()
                .filter(entry -> canonical.containsKey(entry.getKey()))
                .map(entry -> entry.getValue().toHit(canonical.get(entry.getKey())))
                .sorted(
                        Comparator.comparingInt(this::authorityTier)
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

    private Map<CanonicalKey, SearchProjection> canonicalProjections(
            List<Accumulator> values
    ) {
        LinkedHashMap<DocumentGeneration, List<String>> grouped =
                new LinkedHashMap<>();
        for (Accumulator value : values) {
            RetrievalHit hit = value.representative;
            grouped.computeIfAbsent(
                    new DocumentGeneration(
                            value.accessLevel,
                            hit.documentId(),
                            value.generation
                    ),
                    ignored -> new ArrayList<>()
            ).add(hit.chunkId());
        }

        LinkedHashMap<CanonicalKey, SearchProjection> result =
                new LinkedHashMap<>();
        grouped.forEach((scope, chunkIds) ->
                projectionRepository.findByDocumentGenerationAndChunkIds(
                                scope.documentId(),
                                scope.generation(),
                                chunkIds.stream().distinct().toList(),
                                Set.of(scope.accessLevel())
                        )
                        .forEach(projection ->
                                result.put(
                                        new CanonicalKey(
                                                projection.accessLevel() > 0
                                                        ? projection.accessLevel()
                                                        : scope.accessLevel(),
                                                projection.documentId(),
                                                projection.generation(),
                                                projection.chunkId()
                                        ),
                                        projection
                                )
                        )
        );
        return result;
    }

    private String rankKey(RetrievalHit hit) {
        Object queryChunkId = hit.metadata().get("queryChunkId");
        return String.valueOf(queryChunkId) + "|" + hit.type();
    }

    private CanonicalKey key(
            RetrievalHit hit,
            long accessLevel,
            long generation
    ) {
        return new CanonicalKey(
                accessLevel,
                hit.documentId(),
                generation,
                hit.chunkId()
        );
    }

    private long effectiveAccessLevel(
            RetrievalHit hit,
            Set<Long> allowed
    ) {
        if (hit.accessLevel() <= 0) {
            return 0L;
        }
        return allowed.contains(hit.accessLevel())
                ? hit.accessLevel()
                : 0L;
    }

    private Double rawScore(RetrievalHit hit) {
        Object score = hit.metadata().get("score");
        return score instanceof Number number && Double.isFinite(number.doubleValue())
                ? number.doubleValue()
                : null;
    }

    private int authorityTier(RetrievalHit hit) {
        Object explicit = hit.metadata().get("authorityTier");
        if (explicit instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (hit.type() == RetrievalType.IDENTIFIER
                || hit.type() == RetrievalType.REFERENCE) {
            return 0;
        }
        return 2;
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
            Map<String, Object> metadata = new LinkedHashMap<>(source.metadata());
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
            Map<String, Object> metadata = new LinkedHashMap<>(projection.metadata());
            metadata.putAll(evidenceMetadata);
            metadata.put("generation", projection.generation());
            metadata.put("language", projection.language());
            metadata.put("sectionPath", projection.sectionPath() == null
                    ? ""
                    : projection.sectionPath());
            metadata.put("chunkIndex", projection.chunkIndex());
            return new RetrievalHit(
                    representative.type(),
                    projection.accessLevel() > 0
                            ? projection.accessLevel()
                            : accessLevel,
                    projection.documentId(),
                    projection.generation(),
                    projection.chunkId(),
                    projection.text(),
                    metadata
            );
        }
    }

    private record DocumentGeneration(
            long accessLevel,
            String documentId,
            long generation
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
