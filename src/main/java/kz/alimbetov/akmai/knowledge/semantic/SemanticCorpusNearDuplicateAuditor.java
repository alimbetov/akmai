package kz.alimbetov.akmai.knowledge.semantic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces review candidates for lexically similar canonical concepts.
 *
 * <p>This class is intentionally advisory: lexical similarity is not sufficient
 * evidence to merge two semantic concepts. The output is meant for corpus review
 * and CI reporting, not automatic deletion.</p>
 */
public final class SemanticCorpusNearDuplicateAuditor {

    static final double DEFAULT_OVERLAP_THRESHOLD = 0.66d;

    private SemanticCorpusNearDuplicateAuditor() {
    }

    public static List<ReviewCandidate> reviewCandidates(
            List<SemanticConcept> concepts
    ) {
        return reviewCandidates(concepts, DEFAULT_OVERLAP_THRESHOLD);
    }

    static List<ReviewCandidate> reviewCandidates(
            List<SemanticConcept> concepts,
            double overlapThreshold
    ) {
        List<SemanticConcept> values = concepts == null
                ? List.of()
                : List.copyOf(concepts);
        if (values.size() < 2) {
            return List.of();
        }

        Map<String, List<Integer>> tokenIndex = new HashMap<>();
        List<Set<String>> tokenSets = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Set<String> tokens = tokens(values.get(i).preferredPhrase());
            tokenSets.add(tokens);
            for (String token : tokens) {
                tokenIndex.computeIfAbsent(token, ignored -> new ArrayList<>())
                        .add(i);
            }
        }

        LinkedHashSet<Long> candidatePairs = new LinkedHashSet<>();
        for (List<Integer> indexes : tokenIndex.values()) {
            for (int left = 0; left < indexes.size(); left++) {
                for (int right = left + 1; right < indexes.size(); right++) {
                    int first = indexes.get(left);
                    int second = indexes.get(right);
                    candidatePairs.add(pairKey(first, second));
                }
            }
        }

        ArrayList<ReviewCandidate> result = new ArrayList<>();
        for (long pair : candidatePairs) {
            int left = (int) (pair >>> 32);
            int right = (int) pair;
            Set<String> leftTokens = tokenSets.get(left);
            Set<String> rightTokens = tokenSets.get(right);
            int shared = intersectionSize(leftTokens, rightTokens);
            if (shared < 2) {
                continue;
            }

            double overlap = (double) shared
                    / Math.min(leftTokens.size(), rightTokens.size());
            if (overlap < overlapThreshold) {
                continue;
            }

            SemanticConcept leftConcept = values.get(left);
            SemanticConcept rightConcept = values.get(right);
            result.add(new ReviewCandidate(
                    leftConcept.id(),
                    leftConcept.preferredPhrase(),
                    rightConcept.id(),
                    rightConcept.preferredPhrase(),
                    overlap,
                    leftConcept.domainId().equals(rightConcept.domainId()),
                    leftConcept.subdomainId().equals(rightConcept.subdomainId())
            ));
        }

        return result.stream()
                .sorted((a, b) -> {
                    int byScore = Double.compare(
                            b.tokenOverlap(),
                            a.tokenOverlap()
                    );
                    if (byScore != 0) {
                        return byScore;
                    }
                    int byLeft = a.leftConceptId()
                            .compareTo(b.leftConceptId());
                    return byLeft != 0
                            ? byLeft
                            : a.rightConceptId().compareTo(b.rightConceptId());
                })
                .toList();
    }

    private static Set<String> tokens(String phrase) {
        String normalized = EnglishSemanticConceptCatalog.normalizePhrase(phrase);
        if (normalized.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(
                Arrays.asList(normalized.split("\\s+"))
        );
    }

    private static int intersectionSize(
            Set<String> left,
            Set<String> right
    ) {
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        int shared = 0;
        for (String token : smaller) {
            if (larger.contains(token)) {
                shared++;
            }
        }
        return shared;
    }

    private static long pairKey(int left, int right) {
        int first = Math.min(left, right);
        int second = Math.max(left, right);
        return ((long) first << 32) | (second & 0xffffffffL);
    }

    public record ReviewCandidate(
            String leftConceptId,
            String leftPhrase,
            String rightConceptId,
            String rightPhrase,
            double tokenOverlap,
            boolean sameDomain,
            boolean sameSubdomain
    ) {
    }
}
