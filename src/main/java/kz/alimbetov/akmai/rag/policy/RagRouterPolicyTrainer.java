package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.rag.learning.RagLearningEventRepository;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import org.springframework.stereotype.Service;

@Service
public class RagRouterPolicyTrainer {

    private static final double LATENCY_OUTLIER_FACTOR = 4.0;

    private final RagLearningEventRepository learningRepository;
    private final RagPolicyRegistryRepository policyRepository;
    private final RouterLearningProperties properties;

    public RagRouterPolicyTrainer(
            RagLearningEventRepository learningRepository,
            RagPolicyRegistryRepository policyRepository,
            RouterLearningProperties properties
    ) {
        this.learningRepository = learningRepository;
        this.policyRepository = policyRepository;
        this.properties = properties;
    }

    public TrainingResult generateAndRegister(String version) {
        if (!properties.enabled()) {
            throw new IllegalStateException("router learning is disabled");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("policy version is required");
        }

        Instant since = Instant.now().minus(properties.lookback());
        List<RagLearningEventRepository.RouterTrainingSample> samples =
                learningRepository.findRouterTrainingSamples(
                        since,
                        properties.maxEvents()
                );
        Candidate candidate = synthesize(samples);
        if (candidate.routes().isEmpty()) {
            throw new IllegalStateException(
                    "insufficient independent grounded evidence for a router candidate"
            );
        }

        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("routes", candidate.routes());
        configuration.put("training", candidate.evidence());
        configuration.put("generatedAt", Instant.now().toString());
        configuration.put("lookback", properties.lookback().toString());
        configuration.put(
                "reinforcementWindow",
                properties.reinforcementWindow().toString()
        );
        configuration.put("sampleCount", samples.size());
        configuration.put("trainer", "evidence-router-v1.1");

        policyRepository.registerCandidate(
                RagPolicyType.RETRIEVAL,
                version,
                Map.copyOf(configuration)
        );
        return new TrainingResult(
                version,
                samples.size(),
                candidate.routes(),
                candidate.evidence()
        );
    }

    Candidate synthesize(
            List<RagLearningEventRepository.RouterTrainingSample> samples
    ) {
        List<RagLearningEventRepository.RouterTrainingSample> safeSamples =
                samples == null ? List.of() : samples;
        LinkedHashMap<String, List<String>> routes = new LinkedHashMap<>();
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();

        for (AdaptiveRetrievalPlanner.QueryClass queryClass
                : AdaptiveRetrievalPlanner.QueryClass.values()) {
            if (queryClass == AdaptiveRetrievalPlanner.QueryClass.ANALYSIS_UNAVAILABLE) {
                continue;
            }
            List<RagLearningEventRepository.RouterTrainingSample> rawClassSamples =
                    safeSamples.stream()
                            .filter(sample -> queryClass.name().equals(sample.queryClass()))
                            .toList();
            BoundedEvidence bounded = bound(rawClassSamples);
            List<RagLearningEventRepository.RouterTrainingSample> classSamples =
                    bounded.samples();

            int distinctSources = distinctAttributedSources(classSamples);
            double sourceShare = maxSourceShare(classSamples);
            double unknownSourceShare = unknownSourceShare(classSamples);
            double latencyOutlierRate = latencyOutlierRate(classSamples);

            String blockedReason = blockedReason(
                    classSamples,
                    distinctSources,
                    sourceShare
            );
            if (blockedReason != null) {
                evidence.put(
                        queryClass.name(),
                        antiPoisoningEvidence(
                                rawClassSamples,
                                bounded,
                                distinctSources,
                                sourceShare,
                                unknownSourceShare,
                                latencyOutlierRate,
                                blockedReason,
                                Map.of()
                        )
                );
                continue;
            }

            EnumSet<RetrievalType> baseline = baseline(queryClass);
            EnumSet<RetrievalType> retained = EnumSet.copyOf(baseline);
            LinkedHashMap<String, Object> laneEvidence = new LinkedHashMap<>();

            for (RetrievalType lane : baseline) {
                double selectedSupport = support(classSamples, lane, false);
                double citedSupport = support(classSamples, lane, true);
                boolean mandatory = mandatory(queryClass, lane);
                boolean keep = mandatory
                        || citedSupport >= properties.optionalLaneMinCitationSupport()
                        || selectedSupport >= properties.optionalLaneMinSelectedSupport();
                if (!keep) {
                    retained.remove(lane);
                }
                laneEvidence.put(
                        lane.name(),
                        Map.of(
                                "mandatory", mandatory,
                                "selectedSupport", selectedSupport,
                                "citedSupport", citedSupport,
                                "retained", keep
                        )
                );
            }

            routes.put(
                    queryClass.name(),
                    retained.stream().map(Enum::name).sorted().toList()
            );
            evidence.put(
                    queryClass.name(),
                    antiPoisoningEvidence(
                            rawClassSamples,
                            bounded,
                            distinctSources,
                            sourceShare,
                            unknownSourceShare,
                            latencyOutlierRate,
                            "ACCEPTED",
                            Map.copyOf(laneEvidence)
                    )
            );
        }
        return new Candidate(Map.copyOf(routes), Map.copyOf(evidence));
    }

    private BoundedEvidence bound(
            List<RagLearningEventRepository.RouterTrainingSample> samples
    ) {
        if (samples == null || samples.isEmpty()) {
            return new BoundedEvidence(List.of(), 0);
        }
        long windowMillis = Math.max(
                1L,
                properties.reinforcementWindow().toMillis()
        );
        Map<SourceWindow, Integer> admitted = new HashMap<>();
        List<RagLearningEventRepository.RouterTrainingSample> kept = new ArrayList<>();
        int dropped = 0;

        for (RagLearningEventRepository.RouterTrainingSample sample : samples) {
            long timestamp = sample.createdAt() == null
                    ? 0L
                    : sample.createdAt().toEpochMilli();
            SourceWindow key = new SourceWindow(
                    sample.sourceFingerprint(),
                    Math.floorDiv(timestamp, windowMillis)
            );
            int count = admitted.getOrDefault(key, 0);
            if (count >= properties.maxSamplesPerSourceWindow()) {
                dropped++;
                continue;
            }
            admitted.put(key, count + 1);
            kept.add(sample);
        }
        return new BoundedEvidence(List.copyOf(kept), dropped);
    }

    private String blockedReason(
            List<RagLearningEventRepository.RouterTrainingSample> samples,
            int distinctSources,
            double sourceShare
    ) {
        if (samples.size() < properties.minDistinctQueriesPerClass()) {
            return "INSUFFICIENT_DISTINCT_QUERIES";
        }
        if (properties.minDistinctSourcesPerClass() > 1
                && distinctSources < properties.minDistinctSourcesPerClass()) {
            return "INSUFFICIENT_DISTINCT_SOURCES";
        }
        if (sourceShare > properties.maxSourceShare() + 1.0e-12) {
            return "SOURCE_CONCENTRATION_EXCEEDED";
        }
        return null;
    }

    private Map<String, Object> antiPoisoningEvidence(
            List<RagLearningEventRepository.RouterTrainingSample> rawSamples,
            BoundedEvidence bounded,
            int distinctSources,
            double sourceShare,
            double unknownSourceShare,
            double latencyOutlierRate,
            String status,
            Map<String, Object> laneEvidence
    ) {
        List<RagLearningEventRepository.RouterTrainingSample> samples =
                bounded.samples();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("rawDistinctQueries", rawSamples == null ? 0 : rawSamples.size());
        result.put("admittedDistinctQueries", samples.size());
        result.put("droppedBySourceWindowCap", bounded.dropped());
        result.put("distinctAttributedSources", distinctSources);
        result.put("maxSourceShare", sourceShare);
        result.put("unknownSourceShare", unknownSourceShare);
        result.put("latencyOutlierRate", latencyOutlierRate);
        result.put("p50LatencyMs", percentileLatency(samples, 0.50));
        result.put("p95LatencyMs", percentileLatency(samples, 0.95));
        result.put("lanes", laneEvidence);
        return Map.copyOf(result);
    }

    private int distinctAttributedSources(
            List<RagLearningEventRepository.RouterTrainingSample> samples
    ) {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        samples.stream()
                .filter(RagLearningEventRepository.RouterTrainingSample::attributedSource)
                .map(RagLearningEventRepository.RouterTrainingSample::sourceFingerprint)
                .forEach(sources::add);
        return sources.size();
    }

    private double maxSourceShare(
            List<RagLearningEventRepository.RouterTrainingSample> samples
    ) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (var sample : samples) {
            counts.merge(sample.sourceFingerprint(), 1, Integer::sum);
        }
        int maximum = counts.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return (double) maximum / samples.size();
    }

    private double unknownSourceShare(
            List<RagLearningEventRepository.RouterTrainingSample> samples
    ) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        long unknown = samples.stream()
                .filter(sample -> !sample.attributedSource())
                .count();
        return (double) unknown / samples.size();
    }

    private double latencyOutlierRate(
            List<RagLearningEventRepository.RouterTrainingSample> samples
    ) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double median = percentileLatency(samples, 0.50);
        double threshold = Math.max(1.0, median) * LATENCY_OUTLIER_FACTOR;
        long outliers = samples.stream()
                .filter(sample -> sample.totalLatencyMs() > threshold)
                .count();
        return (double) outliers / samples.size();
    }

    private double support(
            List<RagLearningEventRepository.RouterTrainingSample> samples,
            RetrievalType lane,
            boolean cited
    ) {
        long supported = samples.stream()
                .filter(sample -> {
                    Map<String, Integer> contributions = cited
                            ? sample.citedLaneContributions()
                            : sample.selectedLaneContributions();
                    return contributions.getOrDefault(lane.name(), 0) > 0;
                })
                .count();
        return samples.isEmpty() ? 0.0 : (double) supported / samples.size();
    }

    private double percentileLatency(
            List<RagLearningEventRepository.RouterTrainingSample> samples,
            double percentile
    ) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = samples.stream()
                .map(RagLearningEventRepository.RouterTrainingSample::totalLatencyMs)
                .sorted()
                .toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private EnumSet<RetrievalType> baseline(
            AdaptiveRetrievalPlanner.QueryClass queryClass
    ) {
        return switch (queryClass) {
            case IDENTIFIER_ONLY -> EnumSet.of(RetrievalType.IDENTIFIER);
            case IDENTIFIER_SEMANTIC -> EnumSet.of(
                    RetrievalType.IDENTIFIER,
                    RetrievalType.VECTOR,
                    RetrievalType.LEXICAL,
                    RetrievalType.REFERENCE
            );
            case IDENTIFIER_CONCEPTUAL -> EnumSet.of(
                    RetrievalType.IDENTIFIER,
                    RetrievalType.VECTOR,
                    RetrievalType.LEXICAL,
                    RetrievalType.REFERENCE,
                    RetrievalType.CONCEPT
            );
            case CONCEPTUAL_EXACT -> EnumSet.of(
                    RetrievalType.VECTOR,
                    RetrievalType.REFERENCE,
                    RetrievalType.CONCEPT
            );
            case CONCEPTUAL_FUZZY -> EnumSet.of(
                    RetrievalType.VECTOR,
                    RetrievalType.LEXICAL,
                    RetrievalType.REFERENCE,
                    RetrievalType.CONCEPT
            );
            case GENERIC -> EnumSet.of(
                    RetrievalType.VECTOR,
                    RetrievalType.LEXICAL,
                    RetrievalType.REFERENCE
            );
            case ANALYSIS_UNAVAILABLE -> EnumSet.noneOf(RetrievalType.class);
        };
    }

    private boolean mandatory(
            AdaptiveRetrievalPlanner.QueryClass queryClass,
            RetrievalType lane
    ) {
        if (lane == RetrievalType.IDENTIFIER) {
            return queryClass == AdaptiveRetrievalPlanner.QueryClass.IDENTIFIER_ONLY
                    || queryClass == AdaptiveRetrievalPlanner.QueryClass.IDENTIFIER_SEMANTIC
                    || queryClass == AdaptiveRetrievalPlanner.QueryClass.IDENTIFIER_CONCEPTUAL;
        }
        if (lane == RetrievalType.VECTOR || lane == RetrievalType.REFERENCE) {
            return queryClass != AdaptiveRetrievalPlanner.QueryClass.IDENTIFIER_ONLY;
        }
        return false;
    }

    private record SourceWindow(String sourceFingerprint, long bucket) {
    }

    private record BoundedEvidence(
            List<RagLearningEventRepository.RouterTrainingSample> samples,
            int dropped
    ) {
        private BoundedEvidence {
            samples = samples == null ? List.of() : List.copyOf(samples);
            dropped = Math.max(0, dropped);
        }
    }

    record Candidate(
            Map<String, List<String>> routes,
            Map<String, Object> evidence
    ) {
    }

    public record TrainingResult(
            String version,
            int sampleCount,
            Map<String, List<String>> routes,
            Map<String, Object> evidence
    ) {
        public TrainingResult {
            routes = routes == null ? Map.of() : Map.copyOf(routes);
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }
    }
}
