package kz.alimbetov.akmai.knowledge.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.quality.RetrievalBenchmarkCase;
import kz.alimbetov.akmai.rag.quality.RetrievalBenchmarkEvaluator;
import kz.alimbetov.akmai.rag.quality.RetrievalBenchmarkResult;
import kz.alimbetov.akmai.rag.retrieval.ContextBudget;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.mockito.Mockito;

final class GraphReplayHarness {

    private static final int BASE_RANKING_SIZE = 12;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContextBudget contextBudget = new ContextBudget(
            new TokenEstimator(),
            RetrievalTestProperties.defaults()
    );
    private final AdaptiveGraphReplayUtility utility =
            new AdaptiveGraphReplayUtility();

    Corpus load(String resourcePath) {
        try (InputStream input = GraphReplayHarness.class
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "replay corpus not found: " + resourcePath
                );
            }
            Corpus corpus = objectMapper.readValue(input, Corpus.class);
            corpus.validate();
            return corpus;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "cannot load replay corpus " + resourcePath,
                    exception
            );
        }
    }

    List<ReplayOutcome> replay(
            Corpus corpus,
            String split,
            List<Double> thresholds
    ) {
        if (corpus == null) {
            throw new IllegalArgumentException("corpus must not be null");
        }
        if (split == null || split.isBlank()) {
            throw new IllegalArgumentException("split must not be blank");
        }
        if (thresholds == null || thresholds.isEmpty()) {
            throw new IllegalArgumentException(
                    "thresholds must not be empty"
            );
        }

        List<ReplayCase> cases = corpus.cases().stream()
                .filter(testCase -> split.equals(testCase.split()))
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "replay split has no cases: " + split
            );
        }

        List<ReplayOutcome> outcomes = new ArrayList<>();
        for (double threshold : thresholds) {
            requireUnit("threshold", threshold);
            for (ReplayCase testCase : cases) {
                outcomes.add(replayCase(
                        corpus,
                        testCase,
                        threshold
                ));
            }
        }
        return List.copyOf(outcomes);
    }

    private ReplayOutcome replayCase(
            Corpus corpus,
            ReplayCase testCase,
            double threshold
    ) {
        List<RetrievalHit> base = baseHits(testCase);
        List<RetrievalHit> graph = graphHits(testCase);
        List<RetrievalHit> all = new ArrayList<>(
                base.size() + graph.size()
        );
        all.addAll(base);
        all.addAll(graph);

        List<RetrievalHit> baseOnly =
                contextBudget.apply(base, testCase.question());
        List<RetrievalHit> appendOnly =
                contextBudget.apply(all, testCase.question());

        AdaptiveGraphCompetitiveAdmission admission =
                new AdaptiveGraphCompetitiveAdmission(
                        new AdaptiveGraphCompetitionProperties(
                                true,
                                2,
                                4,
                                threshold
                        ),
                        Mockito.mock(AkmaiMetrics.class)
                );
        List<RetrievalHit> competitive = contextBudget.apply(
                admission.admit(all),
                testCase.question()
        );

        RetrievalBenchmarkCase benchmarkCase =
                new RetrievalBenchmarkCase(
                        testCase.id(),
                        testCase.language(),
                        testCase.question(),
                        Set.copyOf(testCase.relevantChunkIds())
                );

        RetrievalBenchmarkResult baseQuality =
                evaluate(benchmarkCase, baseOnly);
        RetrievalBenchmarkResult appendQuality =
                evaluate(benchmarkCase, appendOnly);
        RetrievalBenchmarkResult competitiveQuality =
                evaluate(benchmarkCase, competitive);

        AdaptiveGraphReplayUtility.Evaluation evaluation =
                utility.evaluate(
                        quality(baseQuality),
                        quality(appendQuality),
                        quality(competitiveQuality),
                        corpus.utilityWeights()
                );

        return new ReplayOutcome(
                testCase.id(),
                threshold,
                evaluation,
                baseQuality,
                appendQuality,
                competitiveQuality
        );
    }

    private RetrievalBenchmarkResult evaluate(
            RetrievalBenchmarkCase benchmarkCase,
            List<RetrievalHit> hits
    ) {
        return RetrievalBenchmarkEvaluator.evaluate(
                benchmarkCase,
                hits.stream()
                        .map(RetrievalHit::chunkId)
                        .toList()
        );
    }

    private AdaptiveGraphReplayUtility.QualitySnapshot quality(
            RetrievalBenchmarkResult result
    ) {
        return new AdaptiveGraphReplayUtility.QualitySnapshot(
                result.recallAt5(),
                result.reciprocalRank(),
                result.ndcgAt10()
        );
    }

    private List<RetrievalHit> baseHits(ReplayCase testCase) {
        List<RetrievalHit> hits = new ArrayList<>(BASE_RANKING_SIZE);
        for (int rank = 1; rank <= BASE_RANKING_SIZE; rank++) {
            String chunkId = testCase.id() + "-base-" + rank;
            if (rank == testCase.baseRelevantRank()) {
                chunkId = testCase.relevantChunkIds().getFirst();
            }
            hits.add(new RetrievalHit(
                    RetrievalType.VECTOR,
                    1L,
                    testCase.id() + "-base-doc-" + rank,
                    1L,
                    chunkId,
                    "base replay text " + rank,
                    Map.of(
                            "language", testCase.language(),
                            "domain", testCase.domain()
                    ),
                    List.of(),
                    1.0 - (rank * 0.01)
            ));
        }
        return List.copyOf(hits);
    }

    private List<RetrievalHit> graphHits(ReplayCase testCase) {
        return testCase.graphCandidates().stream()
                .map(candidate -> new RetrievalHit(
                        RetrievalType.GRAPH,
                        1L,
                        testCase.id() + "-graph-doc-" + candidate.chunkId(),
                        1L,
                        candidate.chunkId(),
                        "graph replay text",
                        Map.of(
                                "language", testCase.language(),
                                "domain", testCase.domain(),
                                "adaptiveGraphBand", candidate.band(),
                                "adaptiveGraphScore", candidate.score(),
                                "adaptiveGraphContributingEdges",
                                candidate.contributingEdges()
                        ),
                        List.of(),
                        candidate.score()
                ))
                .toList();
    }

    record ReplayOutcome(
            String replayKey,
            double threshold,
            AdaptiveGraphReplayUtility.Evaluation utility,
            RetrievalBenchmarkResult baseOnly,
            RetrievalBenchmarkResult appendOnly,
            RetrievalBenchmarkResult competitive
    ) {
        AdaptiveGraphThresholdCalibrator.ReplayObservation
                toCalibrationObservation(double latencyDeltaMillis) {
            return new AdaptiveGraphThresholdCalibrator.ReplayObservation(
                    replayKey,
                    threshold,
                    utility.overallUtilityDelta(),
                    utility.appendOnlyUtilityDelta(),
                    utility.competitionUtilityDelta(),
                    latencyDeltaMillis,
                    utility.safetyViolation()
            );
        }
    }

    record Corpus(
            String corpusVersion,
            String utilityContractVersion,
            List<Double> thresholds,
            AdaptiveGraphReplayUtility.Weights utilityWeights,
            Policy policy,
            List<ReplayCase> cases
    ) {
        void validate() {
            if (corpusVersion == null || corpusVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "corpusVersion must not be blank"
                );
            }
            if (!AdaptiveGraphReplayUtility.CONTRACT_VERSION.equals(
                    utilityContractVersion
            )) {
                throw new IllegalArgumentException(
                        "unsupported utility contract version: "
                                + utilityContractVersion
                );
            }
            if (thresholds == null || thresholds.isEmpty()) {
                throw new IllegalArgumentException(
                        "corpus thresholds must not be empty"
                );
            }
            thresholds.forEach(value -> requireUnit(
                    "corpus threshold",
                    value
            ));
            if (utilityWeights == null || policy == null) {
                throw new IllegalArgumentException(
                        "utility weights and policy are required"
                );
            }
            if (cases == null || cases.isEmpty()) {
                throw new IllegalArgumentException(
                        "replay corpus must contain cases"
                );
            }
            cases.forEach(ReplayCase::validate);
        }
    }

    record Policy(
            int minimumSamples,
            double minimumMeaningfulCompetitionDelta,
            double minimumMeanAppendOnlyUtilityLift,
            double minimumMeanOverallUtilityLift,
            double minimumMeanCompetitionUtilityLift,
            double minimumBenefitProbabilityLowerBound,
            double confidenceZ,
            double maximumRegressionRate,
            double maximumP95LatencyRegressionMillis
    ) {
        AdaptiveGraphThresholdCalibrator.CalibrationPolicy
                toCalibrationPolicy() {
            return new AdaptiveGraphThresholdCalibrator.CalibrationPolicy(
                    minimumSamples,
                    minimumMeaningfulCompetitionDelta,
                    minimumMeanAppendOnlyUtilityLift,
                    minimumMeanOverallUtilityLift,
                    minimumMeanCompetitionUtilityLift,
                    minimumBenefitProbabilityLowerBound,
                    confidenceZ,
                    maximumRegressionRate,
                    maximumP95LatencyRegressionMillis
            );
        }
    }

    record ReplayCase(
            String id,
            String split,
            String language,
            String domain,
            String question,
            List<String> relevantChunkIds,
            int baseRelevantRank,
            List<GraphCandidate> graphCandidates
    ) {
        void validate() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                        "replay case id must not be blank"
                );
            }
            if (!Set.of("CALIBRATION", "HOLDOUT").contains(split)) {
                throw new IllegalArgumentException(
                        "unsupported replay split: " + split
                );
            }
            if (language == null
                    || language.isBlank()
                    || domain == null
                    || domain.isBlank()
                    || question == null
                    || question.isBlank()) {
                throw new IllegalArgumentException(
                        "replay case metadata must not be blank"
                );
            }
            if (relevantChunkIds == null
                    || relevantChunkIds.size() != 1) {
                throw new IllegalArgumentException(
                        "replay v1 requires exactly one relevant chunk"
                );
            }
            if (baseRelevantRank < 0
                    || baseRelevantRank > BASE_RANKING_SIZE) {
                throw new IllegalArgumentException(
                        "baseRelevantRank must be in [0, 12]"
                );
            }
            if (graphCandidates == null || graphCandidates.isEmpty()) {
                throw new IllegalArgumentException(
                        "replay case must contain graph candidates"
                );
            }
            graphCandidates.forEach(GraphCandidate::validate);
        }
    }

    record GraphCandidate(
            String chunkId,
            double score,
            String band,
            int contributingEdges
    ) {
        void validate() {
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException(
                        "graph candidate chunkId must not be blank"
                );
            }
            requireUnit("graph candidate score", score);
            if (!Set.of("HOT", "WARM").contains(band)) {
                throw new IllegalArgumentException(
                        "unsupported graph candidate band: " + band
                );
            }
            if (contributingEdges < 1) {
                throw new IllegalArgumentException(
                        "contributingEdges must be positive"
                );
            }
        }
    }

    private static void requireUnit(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and in [0, 1]"
            );
        }
    }
}
