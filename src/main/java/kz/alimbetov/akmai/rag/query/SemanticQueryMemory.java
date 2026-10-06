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
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SemanticQueryMemory {

    private static final int MAX_OBSERVATIONS_PER_CLUSTER = 4;
    private static final int MAX_SOURCE_REFS_PER_OBSERVATION = 12;
    private static final int MAX_CENTROID_WEIGHT = 32;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingProfileService profileService;
    private final AdvancedRetrievalProperties properties;
    private final Cache<String, MemoryCluster> clusters;
    private final Object mutationLock = new Object();
    private PersistentExperienceMemoryRepository persistentRepository;

    public SemanticQueryMemory(
            @Qualifier("retrievalEmbeddingModel") EmbeddingModel embeddingModel,
            EmbeddingProfileService profileService,
            AdvancedRetrievalProperties properties
    ) {
        this.embeddingModel = embeddingModel;
        this.profileService = profileService;
        this.properties = properties;
        this.clusters = Caffeine.newBuilder()
                .maximumSize(properties.queryMemoryMaxEntries())
                .build();
    }

    @Autowired(required = false)
    void setPersistentRepository(
            PersistentExperienceMemoryRepository persistentRepository
    ) {
        this.persistentRepository = persistentRepository;
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
        String profileId = activeProfileId();
        if (profileId == null) {
            return List.of();
        }
        float[] queryVector = safeEmbed(question);
        if (queryVector == null) {
            return List.of();
        }

        if (persistentRepository != null) {
            return persistentMatches(profileId, scope, queryVector);
        }

        return clusters.asMap().values().stream()
                .filter(cluster -> profileId.equals(cluster.embeddingProfileId()))
                .filter(cluster -> scope.equals(cluster.requiredAccessLevels()))
                .map(cluster -> new MemoryMatch(
                        cluster.id(),
                        cosine(queryVector, cluster.centroid()),
                        cluster.observationCount(),
                        cluster.observations()
                ))
                .filter(match -> Double.isFinite(match.similarity()))
                .filter(match -> match.similarity()
                        >= properties.queryMemorySimilarityThreshold())
                .sorted(Comparator
                        .comparingDouble(MemoryMatch::similarity)
                        .reversed()
                        .thenComparing(
                                Comparator.comparingInt(MemoryMatch::observationCount)
                                        .reversed()
                        ))
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
        ArrayList<PersistentExperienceMemoryRepository.SourceKey> sourceKeys =
                new ArrayList<>();
        for (var source : validation.citedSources()) {
            int index = source.number() - 1;
            if (index < 0 || index >= finalContext.size()) {
                continue;
            }
            RetrievalHit hit = finalContext.get(index);
            if (!hit.hasRoutingIdentity()) {
                continue;
            }
            requiredAccessLevels.add(hit.accessLevel());
            if (sourceRefs.size() < MAX_SOURCE_REFS_PER_OBSERVATION) {
                sourceRefs.add(hit.documentId() + ":" + hit.chunkId());
                sourceKeys.add(new PersistentExperienceMemoryRepository.SourceKey(
                        hit.accessLevel(),
                        hit.documentId(),
                        hit.generation(),
                        hit.chunkId()
                ));
            }
        }
        if (requiredAccessLevels.isEmpty() || sourceKeys.isEmpty()) {
            return;
        }

        String profileId = activeProfileId();
        if (profileId == null) {
            return;
        }
        float[] vector = safeEmbed(question);
        if (vector == null) {
            return;
        }

        Set<Long> requiredScope = Set.copyOf(requiredAccessLevels);
        Instant observedAt = Instant.now();
        String normalizedQuestion = normalizeQuestion(question);
        String groundedAnswer = truncate(
                validation.answer(),
                properties.queryMemoryAnswerMaxChars()
        );
        MemoryObservation observation = new MemoryObservation(
                normalizedQuestion,
                groundedAnswer,
                List.copyOf(sourceRefs),
                observedAt
        );

        recordPersistent(
                profileId,
                requiredScope,
                vector,
                normalizedQuestion,
                groundedAnswer,
                List.copyOf(sourceKeys),
                observedAt
        );

        synchronized (mutationLock) {
            MemoryCluster nearest = clusters.asMap().values().stream()
                    .filter(cluster -> profileId.equals(cluster.embeddingProfileId()))
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
                        profileId,
                        vector.clone(),
                        requiredScope,
                        1,
                        List.of(observation)
                ));
                return;
            }

            MemoryCluster current = clusters.getIfPresent(nearest.id());
            if (current == null
                    || !profileId.equals(current.embeddingProfileId())
                    || !current.requiredAccessLevels().equals(requiredScope)
                    || current.centroid().length != vector.length) {
                return;
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
            clusters.put(nearest.id(), new MemoryCluster(
                    nearest.id(),
                    current.embeddingProfileId(),
                    centroid,
                    current.requiredAccessLevels(),
                    current.observationCount() + 1,
                    List.copyOf(observations)
            ));
        }
    }

    private List<MemoryMatch> persistentMatches(
            String profileId,
            Set<Long> scope,
            float[] queryVector
    ) {
        try {
            return persistentRepository.find(
                            profileId,
                            scope,
                            queryVector,
                            properties.queryMemorySimilarityThreshold(),
                            properties.queryMemoryMatches()
                    ).stream()
                    .map(entry -> new MemoryMatch(
                            "pg:" + entry.id(),
                            entry.similarity(),
                            1,
                            List.of(new MemoryObservation(
                                    entry.normalizedQuestion(),
                                    entry.groundedAnswer(),
                                    entry.sources().stream()
                                            .map(source -> source.documentId()
                                                    + ":" + source.chunkId())
                                            .toList(),
                                    entry.observedAt()
                            ))
                    ))
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private void recordPersistent(
            String profileId,
            Set<Long> scope,
            float[] vector,
            String normalizedQuestion,
            String groundedAnswer,
            List<PersistentExperienceMemoryRepository.SourceKey> sourceKeys,
            Instant observedAt
    ) {
        if (persistentRepository == null) {
            return;
        }
        try {
            persistentRepository.record(
                    profileId,
                    scope,
                    vector,
                    normalizedQuestion,
                    groundedAnswer,
                    sourceKeys,
                    observedAt
            );
        } catch (RuntimeException exception) {
            // Experience memory is an optimization. Retrieval must remain available
            // if persistence is temporarily unavailable.
        }
    }

    private String activeProfileId() {
        try {
            return profileService.activeProfile().profileId();
        } catch (RuntimeException exception) {
            return null;
        }
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
            if (accessLevel != null && accessLevel > 0) {
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
            int observationCount,
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
            String embeddingProfileId,
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
