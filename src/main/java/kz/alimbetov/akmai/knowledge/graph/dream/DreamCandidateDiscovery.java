package kz.alimbetov.akmai.knowledge.graph.dream;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.SemanticNeighborSearchRepository;
import kz.alimbetov.akmai.knowledge.graph.SemanticNeighborSearchRepository.SemanticNeighbor;
import org.springframework.stereotype.Component;

/**
 * DREAM-4B shadow discovery. Persists Dream-owned candidate observations only;
 * it never writes knowledge_chunk_association.
 */
@Component
public class DreamCandidateDiscovery {

    private final SemanticNeighborSearchRepository neighbors;
    private final DreamReciprocalNeighborVerifier reciprocalVerifier;
    private final DreamConfidenceCalculator confidenceCalculator;
    private final DreamCandidateRepository candidates;
    private final DreamMetrics metrics;

    public DreamCandidateDiscovery(
            SemanticNeighborSearchRepository neighbors,
            DreamReciprocalNeighborVerifier reciprocalVerifier,
            DreamConfidenceCalculator confidenceCalculator,
            DreamCandidateRepository candidates,
            DreamMetrics metrics
    ) {
        this.neighbors = neighbors;
        this.reciprocalVerifier = reciprocalVerifier;
        this.confidenceCalculator = confidenceCalculator;
        this.candidates = candidates;
        this.metrics = metrics;
    }

    public DiscoveryReport discover(
            List<DreamSourceRepository.DreamSource> sources,
            UUID runId,
            DreamLeaseManager.Authority authority,
            DreamPolicyResolver.ResolvedDreamPolicy policy,
            DreamBudget budget
    ) {
        if (sources == null || runId == null || authority == null
                || policy == null || budget == null) {
            throw new IllegalArgumentException("Dream discovery inputs are required");
        }
        Set<DreamPair> observedThisRun = new HashSet<>();
        int processedSources = 0;
        int persisted = 0;
        int mutual = 0;
        int activated = 0;

        for (DreamSourceRepository.DreamSource source : sources) {
            budget.acquireSource();
            budget.acquireForwardAnn();
            metrics.source("fast");
            metrics.ann("forward");
            processedSources++;

            int searchLimit = Math.min(256, policy.dream().topK() + 1);
            List<SemanticNeighbor> forward = neighbors.search(
                    source.embedding(),
                    source.language(),
                    source.node().accessLevel(),
                    searchLimit,
                    policy.dream().candidateThreshold(),
                    policy.sameLanguageOnly()
            ).stream()
                    .filter(candidate -> !candidate.node().equals(source.node()))
                    .limit(policy.dream().topK())
                    .toList();

            int activatedForSource = 0;
            for (int index = 0; index < forward.size(); index++) {
                SemanticNeighbor neighbor = forward.get(index);
                DreamPair pair = DreamPair.of(source.node(), neighbor.node());
                if (!observedThisRun.add(pair)) {
                    continue;
                }

                int forwardRank = index + 1;
                DreamBudget.Snapshot before = budget.snapshot();
                DreamReciprocalNeighborVerifier.Verification verification =
                        reciprocalVerifier.verify(
                                source,
                                neighbor,
                                forwardRank,
                                policy,
                                budget
                        );
                DreamBudget.Snapshot after = budget.snapshot();
                if (after.reverseAnnQueries() > before.reverseAnnQueries()) {
                    metrics.ann("reverse");
                }
                metrics.cache(verification.cacheHit());

                if (!verification.lifecycleEligible()) {
                    continue;
                }
                metrics.mutual(verification.mutualKnn());

                double confidence = confidenceCalculator.calculate(
                        verification.forwardSimilarity(),
                        verification.reverseSimilarity(),
                        verification.forwardRank(),
                        verification.reverseRank(),
                        policy.dream().topK(),
                        verification.mutualKnn()
                );
                metrics.confidence(confidence);

                boolean mayActivate = verification.mutualKnn()
                        && confidence >= policy.dream().activationThreshold()
                        && activatedForSource
                        < policy.dream().maxNewEdgesPerChunk();
                DreamCandidateRepository.CandidateState state = mayActivate
                        ? DreamCandidateRepository.CandidateState.ACTIVE
                        : DreamCandidateRepository.CandidateState.CANDIDATE;
                if (mayActivate) {
                    activatedForSource++;
                    activated++;
                }

                NormalizedEvidence evidence = normalize(
                        source.node(), pair, verification
                );
                candidates.observe(
                        authority,
                        new DreamCandidateRepository.Observation(
                                pair,
                                policy.graphVersion(),
                                policy.semanticPolicyVersion(),
                                policy.fingerprint(),
                                state,
                                evidence.firstToSecondSimilarity(),
                                evidence.secondToFirstSimilarity(),
                                evidence.firstToSecondRank(),
                                evidence.secondToFirstRank(),
                                verification.mutualKnn(),
                                confidence,
                                source.embeddingProfileId(),
                                runId,
                                "reciprocal-ann-v1",
                                verification.mutualKnn()
                                        ? DreamCandidateRepository.ObservationOutcome.POSITIVE
                                        : DreamCandidateRepository.ObservationOutcome.NEGATIVE_SEMANTIC,
                                Instant.now()
                        )
                );
                budget.addDbRows(1);
                persisted++;
                if (verification.mutualKnn()) {
                    mutual++;
                }
                metrics.candidate(state.name());
            }
        }

        return new DiscoveryReport(
                processedSources,
                persisted,
                mutual,
                activated,
                budget.snapshot()
        );
    }

    private NormalizedEvidence normalize(
            ChunkGraphNode source,
            DreamPair pair,
            DreamReciprocalNeighborVerifier.Verification verification
    ) {
        if (source.equals(pair.first())) {
            return new NormalizedEvidence(
                    verification.forwardSimilarity(),
                    verification.reverseSimilarity(),
                    verification.forwardRank(),
                    verification.reverseRank()
            );
        }
        return new NormalizedEvidence(
                verification.reverseSimilarity(),
                verification.forwardSimilarity(),
                verification.reverseRank(),
                verification.forwardRank()
        );
    }

    private record NormalizedEvidence(
            double firstToSecondSimilarity,
            double secondToFirstSimilarity,
            int firstToSecondRank,
            int secondToFirstRank
    ) {
    }

    public record DiscoveryReport(
            int processedSources,
            int persistedCandidates,
            int mutualCandidates,
            int activatedCandidates,
            DreamBudget.Snapshot budget
    ) {
    }
}
