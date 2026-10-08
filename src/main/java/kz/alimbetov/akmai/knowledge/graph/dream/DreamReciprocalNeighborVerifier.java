package kz.alimbetov.akmai.knowledge.graph.dream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Optional;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.SemanticNeighborSearchRepository;
import kz.alimbetov.akmai.knowledge.graph.SemanticNeighborSearchRepository.SemanticNeighbor;
import org.springframework.stereotype.Component;

/** Reverse top-K verification for mutual semantic neighbourhoods. */
@Component
public class DreamReciprocalNeighborVerifier {

    private final DreamSourceRepository sources;
    private final SemanticNeighborSearchRepository neighbors;
    private final Cache<CacheKey, List<SemanticNeighbor>> reverseCache;

    public DreamReciprocalNeighborVerifier(
            DreamSourceRepository sources,
            SemanticNeighborSearchRepository neighbors,
            AdaptiveGraphProperties properties
    ) {
        this.sources = sources;
        this.neighbors = neighbors;
        this.reverseCache = Caffeine.newBuilder()
                .maximumSize(properties.dream().reverseCacheMaximumSize())
                .build();
    }

    public Verification verify(
            DreamSourceRepository.DreamSource source,
            SemanticNeighbor forwardNeighbor,
            int forwardRank,
            DreamPolicyResolver.ResolvedDreamPolicy policy,
            DreamBudget budget
    ) {
        if (source == null || forwardNeighbor == null || policy == null
                || budget == null || forwardRank <= 0) {
            throw new IllegalArgumentException("invalid reciprocal verification input");
        }
        ChunkGraphNode targetNode = forwardNeighbor.node();
        Optional<DreamSourceRepository.DreamSource> currentTarget =
                sources.findEligibleSource(targetNode);
        Optional<DreamSourceRepository.DreamSource> currentSource =
                sources.findEligibleSource(source.node());
        if (currentTarget.isEmpty() || currentSource.isEmpty()) {
            return Verification.ineligible(
                    forwardNeighbor.similarity(),
                    forwardRank,
                    policy.dream().topK() + 1
            );
        }

        CacheKey cacheKey = new CacheKey(targetNode, policy.fingerprint());
        List<SemanticNeighbor> reverse = reverseCache.getIfPresent(cacheKey);
        boolean cacheHit = reverse != null;
        if (!cacheHit) {
            budget.acquireReverseAnn();
            DreamSourceRepository.DreamSource target = currentTarget.get();
            int searchLimit = Math.min(256, policy.dream().topK() + 1);
            reverse = neighbors.search(
                    target.embedding(),
                    target.language(),
                    target.node().accessLevel(),
                    searchLimit,
                    policy.dream().candidateThreshold(),
                    policy.sameLanguageOnly()
            ).stream()
                    .filter(candidate -> !candidate.node().equals(target.node()))
                    .limit(policy.dream().topK())
                    .toList();
            reverseCache.put(cacheKey, reverse);
        }

        for (int index = 0; index < reverse.size(); index++) {
            SemanticNeighbor candidate = reverse.get(index);
            if (candidate.node().equals(source.node())) {
                return new Verification(
                        true,
                        true,
                        forwardNeighbor.similarity(),
                        candidate.similarity(),
                        forwardRank,
                        index + 1,
                        cacheHit
                );
            }
        }
        return new Verification(
                true,
                false,
                forwardNeighbor.similarity(),
                0.0,
                forwardRank,
                policy.dream().topK() + 1,
                cacheHit
        );
    }

    public void clearCache() {
        reverseCache.invalidateAll();
    }

    private record CacheKey(ChunkGraphNode node, String policyFingerprint) {
    }

    public record Verification(
            boolean lifecycleEligible,
            boolean mutualKnn,
            double forwardSimilarity,
            double reverseSimilarity,
            int forwardRank,
            int reverseRank,
            boolean cacheHit
    ) {
        static Verification ineligible(
                double forwardSimilarity,
                int forwardRank,
                int reverseRank
        ) {
            return new Verification(
                    false,
                    false,
                    forwardSimilarity,
                    0.0,
                    forwardRank,
                    reverseRank,
                    false
            );
        }
    }
}
