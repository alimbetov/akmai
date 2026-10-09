package kz.alimbetov.akmai.knowledge.graph.dream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.SemanticNeighborSearchRepository;
import kz.alimbetov.akmai.knowledge.graph.SemanticNeighborSearchRepository.SemanticNeighbor;
import org.springframework.stereotype.Component;

/**
 * DREAM-4B discovery plus DREAM-5 restricted semantic-prior materialization.
 * Production graph writes are possible only through SemanticGraphPriorWriter,
 * which independently enforces the apply gate, lifecycle, degree and fencing.
 */
@Component
public class DreamCandidateDiscovery {

    private final SemanticNeighborSearchRepository neighbors;
    private final DreamReciprocalNeighborVerifier reciprocalVerifier;
    private final DreamConfidenceCalculator confidenceCalculator;
    private final DreamCandidateRepository candidates;
    private final SemanticGraphPriorWriter priorWriter;
    private final DreamMetrics metrics;
    private final Cache<UUID, Set<DreamPair>> observedPairsByRun =
            Caffeine.newBuilder().maximumSize(1024).build();

    public DreamCandidateDiscovery(
            SemanticNeighborSearchRepository neighbors,
            DreamReciprocalNeighborVerifier reciprocalVerifier,
            DreamConfidenceCalculator confidenceCalculator,
            DreamCandidateRepository candidates,
            SemanticGraphPriorWriter priorWriter,
            DreamMetrics metrics
    ) {
        this.neighbors = neighbors;
        this.reciprocalVerifier = reciprocalVerifier;
        this.confidenceCalculator = confidenceCalculator;
        this.candidates = candidates;
        this.priorWriter = priorWriter;
        this.metrics = metrics;
    }

    public DiscoveryReport discover(
            List<DreamSourceRepository.DreamSource> sources,
            UUID runId,
            DreamLeaseManager.Authority authority,
            DreamPolicyResolver.ResolvedDreamPolicy policy,
            DreamBudget budget,
            String lane
    ) {
        if (sources == null || runId == null || authority == null
                || policy == null || budget == null
                || lane == null || lane.isBlank()) {
            throw new IllegalArgumentException("Dream discovery inputs are required");
        }
        Set<DreamPair> observedThisRun = observedPairsByRun.get(
                runId,
                ignored -> ConcurrentHashMap.newKeySet()
        );
        int processedSources = 0;
        int persisted = 0;
        int mutual = 0;
        int activated = 0;
        int applied = 0;

        for (DreamSourceRepository.DreamSource source : sources) {
            budget.acquireSource();
            budget.acquireForwardAnn();
            metrics.source(lane);
            metrics.ann("forward");
            processedSources++;

            int searchLimit = Math.min(256, policy.dream().topK() + 1);
            List<SemanticNeighbor> forward = neighbors.search(
                    source.embedding(),
                    source.language(),
                    source.node().accessLevel(),
                    searchLimit,
                    policy.dream().candidateThreshold(),
                    policy.sameLanguageOnly(),
                    policy.dream().queryTimeout()
            ).stream()
                    .filter(candidate -> !candidate.node().equals(source.node()))
                    .limit(policy.dream().topK())
                    .toList();

            Set<DreamPair> activePairs = new HashSet<>(
                    candidates.findActivePairsForSource(
                            policy.graphVersion(),
                            policy.fingerprint(),
                            source.node()
                    )
            );
            Set<DreamPair> currentForwardPairs = new HashSet<>();
            for (SemanticNeighbor neighbor : forward) {
                currentForwardPairs.add(DreamPair.of(
                        source.node(),
                        neighbor.node()
                ));
            }

            Instant scanObservedAt = Instant.now();
            for (DreamPair activePair : activePairs) {
                if (!currentForwardPairs.contains(activePair)) {
                    if (markStale(
                            authority,
                            activePair,
                            runId,
                            "not-in-forward-topk",
                            scanObservedAt,
                            budget
                    )) {
                        persisted++;
                        metrics.candidate(
                                DreamCandidateRepository.CandidateState.STALE.name()
                        );
                    }
                }
            }

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
                                runId,
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
                    if (markStale(
                            authority,
                            pair,
                            runId,
                            "lifecycle-ineligible",
                            Instant.now(),
                            budget
                    )) {
                        persisted++;
                        metrics.candidate(
                                DreamCandidateRepository.CandidateState.STALE.name()
                        );
                    }
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

                boolean wasActive = activePairs.contains(pair);
                boolean retainActive = wasActive
                        && verification.mutualKnn()
                        && confidence >= policy.dream().retentionThreshold();
                boolean mayActivate = !wasActive
                        && verification.mutualKnn()
                        && confidence >= policy.dream().activationThreshold()
                        && activatedForSource
                        < policy.dream().maxNewEdgesPerChunk();
                DreamCandidateRepository.CandidateState state;
                if (retainActive || mayActivate) {
                    state = DreamCandidateRepository.CandidateState.ACTIVE;
                } else if (wasActive) {
                    state = DreamCandidateRepository.CandidateState.STALE;
                } else {
                    state = DreamCandidateRepository.CandidateState.CANDIDATE;
                }
                if (mayActivate) {
                    activatedForSource++;
                    activated++;
                }

                NormalizedEvidence evidence = normalize(
                        source.node(), pair, verification
                );
                Instant observedAt = Instant.now();
                reserveCandidateObservation(
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
                                observedAt
                        ),
                        budget
                );
                persisted++;
                if (verification.mutualKnn()) {
                    mutual++;
                }
                metrics.candidate(state.name());

                if (state == DreamCandidateRepository.CandidateState.ACTIVE) {
                    double semanticSimilarity = Math.min(
                            verification.forwardSimilarity(),
                            verification.reverseSimilarity()
                    );
                    SemanticGraphPriorWriter.ApplyResult applyResult =
                            reservePriorApply(
                                    authority,
                                    pair,
                                    semanticSimilarity,
                                    observedAt,
                                    budget
                            );
                    metrics.apply(applyResult.name());
                    if (applyResult == SemanticGraphPriorWriter.ApplyResult.APPLIED
                            || applyResult == SemanticGraphPriorWriter.ApplyResult.REFRESHED) {
                        applied++;
                    }
                }
            }
        }

        return new DiscoveryReport(
                processedSources,
                persisted,
                mutual,
                activated,
                applied,
                budget.snapshot()
        );
    }

    void clearRun(UUID runId) {
        if (runId != null) {
            observedPairsByRun.invalidate(runId);
        }
    }

    private boolean markStale(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            UUID runId,
            String reason,
            Instant observedAt,
            DreamBudget budget
    ) {
        budget.addDbRows(1);
        boolean keepReservation = false;
        try {
            boolean changed = candidates.markStaleIfActive(
                    authority,
                    pair,
                    runId,
                    reason,
                    observedAt
            );
            keepReservation = changed;
            return changed;
        } finally {
            if (!keepReservation) {
                budget.releaseDbRows(1);
            }
        }
    }

    private void reserveCandidateObservation(
            DreamLeaseManager.Authority authority,
            DreamCandidateRepository.Observation observation,
            DreamBudget budget
    ) {
        budget.addDbRows(1);
        boolean keepReservation = false;
        try {
            candidates.observe(authority, observation);
            keepReservation = true;
        } finally {
            if (!keepReservation) {
                budget.releaseDbRows(1);
            }
        }
    }

    private SemanticGraphPriorWriter.ApplyResult reservePriorApply(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            double semanticSimilarity,
            Instant observedAt,
            DreamBudget budget
    ) {
        budget.addDbRows(2);
        boolean keepReservation = false;
        try {
            SemanticGraphPriorWriter.ApplyResult result =
                    priorWriter.applyCandidate(
                            authority,
                            pair,
                            semanticSimilarity,
                            observedAt
                    );
            keepReservation =
                    result == SemanticGraphPriorWriter.ApplyResult.APPLIED
                    || result == SemanticGraphPriorWriter.ApplyResult.REFRESHED;
            return result;
        } finally {
            if (!keepReservation) {
                budget.releaseDbRows(2);
            }
        }
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
            int appliedSemanticPriors,
            DreamBudget.Snapshot budget
    ) {
    }
}
