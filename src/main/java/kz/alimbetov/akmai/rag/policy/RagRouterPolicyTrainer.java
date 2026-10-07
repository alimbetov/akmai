package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.rag.learning.RagLearningEventRepository;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import org.springframework.stereotype.Service;

@Service
public class RagRouterPolicyTrainer {

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
            List<RagLearningEventRepository.RouterTrainingSample> classSamples =
                    safeSamples.stream()
                            .filter(sample -> queryClass.name().equals(sample.queryClass()))
                            .toList();
            if (classSamples.size() < properties.minDistinctQueriesPerClass()) {
                continue;
            }

            EnumSet<RetrievalType> baseline = baseline(queryClass);
            EnumSet<RetrievalType> retained = EnumSet.copyOf(baseline);
            LinkedHashMap<String, Object> laneEvidence = new LinkedHashMap<>();

            for (RetrievalType lane : baseline) {
                double selectedSupport = support(
                        classSamples,
                        lane,
                        false
                );
                double citedSupport = support(
                        classSamples,
                        lane,
                        true
                );
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
                    Map.of(
                            "distinctQueries", classSamples.size(),
                            "lanes", Map.copyOf(laneEvidence),
                            "p50LatencyMs", percentileLatency(classSamples, 0.50),
                            "p95LatencyMs", percentileLatency(classSamples, 0.95)
                    )
            );
        }
        return new Candidate(Map.copyOf(routes), Map.copyOf(evidence));
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
