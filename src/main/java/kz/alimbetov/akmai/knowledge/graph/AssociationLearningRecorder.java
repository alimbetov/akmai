package kz.alimbetov.akmai.knowledge.graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AssociationLearningRecorder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AssociationLearningRecorder.class);

    private final AdaptiveGraphProperties properties;
    private final AdaptiveChunkGraphRepository repository;
    private final PrivacySafeQueryFingerprint fingerprint;
    private final AkmaiMetrics metrics;

    public AssociationLearningRecorder(
            AdaptiveGraphProperties properties,
            AdaptiveChunkGraphRepository repository,
            PrivacySafeQueryFingerprint fingerprint,
            AkmaiMetrics metrics
    ) {
        this.properties = properties;
        this.repository = repository;
        this.fingerprint = fingerprint;
        this.metrics = metrics;
    }

    public void record(
            List<QueryChunk> queryChunks,
            Set<Long> allowedAccessLevels,
            List<RetrievalHit> boundedContext,
            CitationValidator.CitationValidation validation
    ) {
        if (!properties.learningEnabled()) {
            return;
        }

        try {
            List<IndexedHit> eligible = eligibleHits(
                    allowedAccessLevels,
                    boundedContext,
                    validation
            );
            if (eligible.size() < 2) {
                return;
            }

            int queryBucket = fingerprint.bucket(queryChunks);
            List<PairCandidate> pairs = pairs(eligible);
            if (pairs.isEmpty()) {
                return;
            }

            int limit = Math.min(
                    pairs.size(),
                    properties.learning().maxPairsPerRequest()
            );
            Instant observedAt = Instant.now();
            List<AssociationObservation> observations =
                    new ArrayList<>(limit);
            int citationPairs = 0;

            for (int index = 0; index < limit; index++) {
                PairCandidate pair = pairs.get(index);
                if (pair.bothCited()) {
                    citationPairs++;
                }
                observations.add(new AssociationObservation(
                        node(pair.left().hit()),
                        node(pair.right().hit()),
                        AssociationBand.CANDIDATE,
                        new AssociationEvidence(
                                0.0,
                                1,
                                1,
                                pair.bothCited() ? 1 : 0,
                                queryBucket,
                                observedAt,
                                properties.graphVersion()
                        )
                ));
            }

            repository.reinforceSymmetricBatch(observations);
            metrics.adaptiveGraphLearning(
                    "context",
                    "accepted",
                    observations.size()
            );
            if (citationPairs > 0) {
                metrics.adaptiveGraphLearning(
                        "citation",
                        "accepted",
                        citationPairs
                );
            }
        } catch (RuntimeException exception) {
            metrics.adaptiveGraphLearning("batch", "failed", 1);
            LOGGER.warn(
                    "adaptive_graph_learning event=failed errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private List<IndexedHit> eligibleHits(
            Set<Long> allowedAccessLevels,
            List<RetrievalHit> boundedContext,
            CitationValidator.CitationValidation validation
    ) {
        if (allowedAccessLevels == null
                || allowedAccessLevels.isEmpty()
                || boundedContext == null
                || boundedContext.isEmpty()
                || validation == null) {
            return List.of();
        }

        Set<Integer> citedNumbers = new HashSet<>();
        validation.citedSources().forEach(
                source -> citedNumbers.add(source.number())
        );

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();

        citedNumbers.stream()
                .sorted()
                .filter(number -> number >= 1
                        && number <= boundedContext.size())
                .forEach(selected::add);

        for (int number = 1;
                number <= boundedContext.size()
                        && selected.size()
                        < properties.learning().maxContextChunks();
                number++) {
            selected.add(number);
        }

        return selected.stream()
                .limit(properties.learning().maxContextChunks())
                .map(number -> new IndexedHit(
                        number,
                        boundedContext.get(number - 1),
                        citedNumbers.contains(number)
                ))
                .filter(indexed -> indexed.hit().hasRoutingIdentity())
                .filter(indexed ->
                        allowedAccessLevels.contains(
                                indexed.hit().accessLevel()
                        ))
                .toList();
    }

    private List<PairCandidate> pairs(List<IndexedHit> hits) {
        List<PairCandidate> result = new ArrayList<>();
        for (int left = 0; left < hits.size(); left++) {
            for (int right = left + 1; right < hits.size(); right++) {
                IndexedHit first = hits.get(left);
                IndexedHit second = hits.get(right);
                if (first.hit().accessLevel()
                        != second.hit().accessLevel()) {
                    continue;
                }
                result.add(new PairCandidate(
                        first,
                        second,
                        first.cited() && second.cited()
                ));
            }
        }

        result.sort(
                Comparator
                        .comparing(PairCandidate::bothCited)
                        .reversed()
                        .thenComparing(
                                PairCandidate::combinedScore,
                                Comparator.reverseOrder()
                        )
                        .thenComparing(
                                pair -> pair.left().hit().documentId()
                        )
                        .thenComparing(
                                pair -> pair.left().hit().chunkId()
                        )
                        .thenComparing(
                                pair -> pair.right().hit().documentId()
                        )
                        .thenComparing(
                                pair -> pair.right().hit().chunkId()
                        )
        );
        return result;
    }

    private ChunkGraphNode node(RetrievalHit hit) {
        return new ChunkGraphNode(
                hit.accessLevel(),
                hit.documentId(),
                hit.generation(),
                hit.chunkId()
        );
    }

    private record IndexedHit(
            int sourceNumber,
            RetrievalHit hit,
            boolean cited
    ) {
    }

    private record PairCandidate(
            IndexedHit left,
            IndexedHit right,
            boolean bothCited
    ) {
        double combinedScore() {
            return left.hit().fusedScore()
                    + right.hit().fusedScore();
        }
    }
}
