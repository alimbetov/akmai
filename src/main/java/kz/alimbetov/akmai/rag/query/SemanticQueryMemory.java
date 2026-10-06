package kz.alimbetov.akmai.rag.query;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SemanticQueryMemory {

    private static final int MAX_OBSERVATIONS_PER_CLUSTER = 4;
    private static final int MAX_SOURCE_REFS_PER_OBSERVATION = 12;
    private static final int MAX_CENTROID_WEIGHT = 32;

    private final EmbeddingModel embeddingModel;
    private final AdvancedRetrievalProperties properties;
    private final Cache<String, MemoryCluster> clusters;

    public SemanticQueryMemory(
            @Qualifier("retrievalEmbeddingModel") EmbeddingModel embeddingModel,
            AdvancedRetrievalProperties properties
    ) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.clusters = Caffeine.newBuilder()
                .maximumSize(properties.queryMemoryMaxEntries())
                .build();
    }

    public List<MemoryMatch> find(
            String question,
            Set<Long> accessLevels
    ) {
        if (!properties.queryMemoryEnabled()
                || question == null
                || question.isBlank()) {
            return List.of();
        }
        Set<Long> scope = normalizeScope(accessLevels);
        if (scope.isEmpty()) {
            return List.of();
        }
        float[] queryVector = safeEmbed(question);
        if (queryVector == null) {
            return List.of();
        }

        return clusters.asMap().values().stream()
                .filter(cluster -> scope.containsAll(cluster.requiredAccessLevels()))
                .map(cluster -> new MemoryMatch(
                        cluster.id(),
                        cosine(queryVector, cluster.centroid()),
                        cluster.observations()
                ))
                .filter(match -> Double.isFinite(match.similarity()))
                .filter(match -> match.similarity()
                        >= properties.queryMemorySimilarityThreshold())
                .sorted(Comparator.comparingDouble(MemoryMatch::similarity).reversed())
                .limit(properties.queryMemoryMatches())
                .toList();
    }

    public void recordGrounded(
            String question,
            List<RetrievalHit> finalContext,
            CitationValidator.CitationValidation validation
    ) {
        if (!properties.queryMemoryEnabled()
                || question == null
                || question.isBlank()
                || finalContext == null
                || finalContext.isEmpty()
                || validation == null
                || validation.answer() == null
                || validation.answer().isBlank()
                || validation.citedSources() == null
                || validation.citedSources().isEmpty()) {
            return;
        }

        LinkedHashSet<Long> requiredAccessLevels = new LinkedHashSet<>();
        LinkedHashSet<String> sourceRefs = new LinkedHashSet<>();
        for (var source : validation.citedSources()) {
            int index = source.number() - 1;
            if (index < 0 || index >= finalContext.size()) {
                continue;
            }
            RetrievalHit hit = finalContext.get(index);
            requiredAccessLevels.add(hit.accessLevel());
            sourceRefs.add(hit.documentId() + ":" + hit.chunkId());
            if (sourceRefs.size() >= MAX_SOURCE_REFS_PER_OBSERVATION) {
                break;
            }
        }
        if (requiredAccessLevels.isEmpty()) {
            return;
        }

        float[] vector = safeEmbed(question);
        if (vector == null) {
            return;
        }

        Set<Long> requiredScope = Set.copyOf(requiredAccessLevels);
        MemoryObservation observation = new MemoryObservation(
                normalizeQuestion(question),
                truncate(validation.answer(), properties.queryMemoryAnswerMaxChars()),
                List.copyOf(sourceRefs),
                Instant.now()
        );

        MemoryCluster nearest = clusters.asMap().values().stream()
                .filter(cluster -> cluster.requiredAccessLevels().equals(requiredScope))
                .filter(cluster -> cluster.centroid().length == vector.length)
                .map(cluster -> new ClusterCandidate(
                        cluster,
                        cosine(vector, cluster.centroid())
                ))
                .filter(candidate -> Double.isFinite(candidate.similarity()))
                .filter(candidate -> candidate.similarity()
                        >= properties.queryMemorySimilarityThreshold())
                .max(Comparator.comparingDouble(ClusterCandidate::similarity))
                .map(ClusterCandidate::cluster)
                .orElse(null);

        if (nearest == null) {
            String id = UUID.randomUUID().toString();
            clusters.put(id, new MemoryCluster(
                    id,
                    vector.clone(),
                    requiredScope,
                    1,
                    List.of(observation)
            ));
            return;
        }

        clusters.asMap().computeIfPresent(nearest.id(), (id, current) -> {
            if (!current.requiredAccessLevels().equals(requiredScope)
                    || current.centroid().length != vector.length) {
                return current;
            }
            int weight = Math.min(current.observationCount(), MAX_CENTROID_WEIGHT);
            float[] centroid = mergeCentroid(current.centroid(), vector, weight);
            List<MemoryObservation> observations = new ArrayList<>(current.observations());
            observations.add(0, observation);
            if (observations.size() > MAX_OBSERVATIONS_PER_CLUSTER) {
                observations = new ArrayList<>(
                        observations.subList(0, MAX_OBSERVATIONS_PER_CLUSTER)
                );
            }
            return new MemoryCluster(
                    id,
                    centroid,
                    current.requiredAccessLevels(),
                    current.observationCount() + 1,
                    List.copyOf(observations)
            );
        });
    }

    private float[] safeEmbed(String text) {
        try {
            float[] vector = embeddingModel.embed(text);
            if (vector == null || vector.length == 0) {
                return null;
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    return null;
                }
            }
            return vector;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private double cosine(float[] left, float[] right) {
        if (left.length != right.length || left.length == 0) {
            return Double.NaN;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private float[] mergeCentroid(float[] centroid, float[] vector, int weight) {
        float[] result = new float[centroid.length];
        double divisor = weight + 1.0;
        for (int i = 0; i < centroid.length; i++) {
            result[i] = (float) ((centroid[i] * weight + vector[i]) / divisor);
        }
        return result;
    }

    private Set<Long> normalizeScope(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long accessLevel : accessLevels) {
            if (accessLevel != null) {
                normalized.add(accessLevel);
            }
        }
        return Set.copyOf(normalized);
    }

    private String normalizeQuestion(String question) {
        return question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String truncate(String text, int maxChars) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    public record MemoryMatch(
            String clusterId,
            double similarity,
            List<MemoryObservation> observations
    ) {
        public MemoryMatch {
            observations = observations == null
                    ? List.of()
                    : List.copyOf(observations);
        }
    }

    public record MemoryObservation(
            String normalizedQuestion,
            String groundedAnswer,
            List<String> sourceRefs,
            Instant observedAt
    ) {
        public MemoryObservation {
            sourceRefs = sourceRefs == null
                    ? List.of()
                    : List.copyOf(sourceRefs);
        }
    }

    private record MemoryCluster(
            String id,
            float[] centroid,
            Set<Long> requiredAccessLevels,
            int observationCount,
            List<MemoryObservation> observations
    ) {
        private MemoryCluster {
            centroid = centroid.clone();
            requiredAccessLevels = Set.copyOf(requiredAccessLevels);
            observations = List.copyOf(observations);
        }
    }

    private record ClusterCandidate(
            MemoryCluster cluster,
            double similarity
    ) {
    }
}
