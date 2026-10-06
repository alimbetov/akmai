package kz.alimbetov.akmai.knowledge.graph;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class IngestionSemanticLinker {

    private final SemanticMemoryProperties properties;
    private final AdaptiveGraphProperties graphProperties;
    private final SemanticNeighborSearchRepository neighborRepository;
    private final SemanticAssociationSeedRepository seedRepository;
    private final AppParameterService appParameterService;

    public IngestionSemanticLinker(
            SemanticMemoryProperties properties,
            AdaptiveGraphProperties graphProperties,
            SemanticNeighborSearchRepository neighborRepository,
            SemanticAssociationSeedRepository seedRepository
    ) {
        this(
                properties,
                graphProperties,
                neighborRepository,
                seedRepository,
                null
        );
    }

    @Autowired
    public IngestionSemanticLinker(
            SemanticMemoryProperties properties,
            AdaptiveGraphProperties graphProperties,
            SemanticNeighborSearchRepository neighborRepository,
            SemanticAssociationSeedRepository seedRepository,
            AppParameterService appParameterService
    ) {
        this.properties = properties;
        this.graphProperties = graphProperties;
        this.neighborRepository = neighborRepository;
        this.seedRepository = seedRepository;
        this.appParameterService = appParameterService;
    }

    public LinkingReport linkPublishedGeneration(
            GenerationIdentity identity,
            List<VectorRow> vectors
    ) {
        if (!enabled()) {
            return LinkingReport.disabled();
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (vectors == null || vectors.isEmpty()) {
            return LinkingReport.empty();
        }

        Set<CanonicalPair> seededPairs = new HashSet<>();
        int searchedChunks = 0;
        int selfRejected = 0;
        int languageRejected = 0;
        int duplicateRejected = 0;
        int budgetRejected = 0;
        int seededEdges = 0;
        Instant observedAt = Instant.now();

        for (VectorRow row : vectors) {
            ChunkGraphNode source = new ChunkGraphNode(
                    identity.accessLevel(),
                    identity.documentId(),
                    identity.generation(),
                    row.chunkId()
            );
            searchedChunks++;

            int searchLimit = Math.min(
                    256,
                    Math.max(
                            properties.getTopK(),
                            properties.getMaxEdgesPerChunk()
                    ) + 1
            );
            List<SemanticNeighborSearchRepository.SemanticNeighbor> neighbors =
                    neighborRepository.search(
                            row.embedding(),
                            row.language(),
                            identity.accessLevel(),
                            searchLimit,
                            properties.getMinSimilarity(),
                            properties.isSameLanguageOnly()
                    );

            LinkedHashSet<ChunkGraphNode> acceptedTargets =
                    new LinkedHashSet<>();
            for (SemanticNeighborSearchRepository.SemanticNeighbor neighbor
                    : neighbors) {
                if (source.equals(neighbor.node())) {
                    selfRejected++;
                    continue;
                }
                if (properties.isSameLanguageOnly()
                        && !row.language().equalsIgnoreCase(neighbor.language())) {
                    languageRejected++;
                    continue;
                }
                if (!acceptedTargets.add(neighbor.node())) {
                    duplicateRejected++;
                    continue;
                }
                if (acceptedTargets.size() > properties.getMaxEdgesPerChunk()) {
                    budgetRejected++;
                    continue;
                }

                CanonicalPair pair = CanonicalPair.of(
                        source,
                        neighbor.node()
                );
                if (!seededPairs.add(pair)) {
                    duplicateRejected++;
                    continue;
                }

                seedRepository.seedSymmetric(
                        source,
                        neighbor.node(),
                        neighbor.similarity(),
                        graphProperties.graphVersion(),
                        observedAt
                );
                seededEdges++;
            }
        }

        return new LinkingReport(
                true,
                searchedChunks,
                seededEdges,
                selfRejected,
                languageRejected,
                duplicateRejected,
                budgetRejected
        );
    }

    private boolean enabled() {
        return appParameterService == null
                ? properties.isIngestionLinkingEnabled()
                : appParameterService.isEnabled(
                        AppParameterKey.SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED
                );
    }

    private record CanonicalPair(
            ChunkGraphNode first,
            ChunkGraphNode second
    ) {
        private static CanonicalPair of(
                ChunkGraphNode left,
                ChunkGraphNode right
        ) {
            return left.compareTo(right) <= 0
                    ? new CanonicalPair(left, right)
                    : new CanonicalPair(right, left);
        }
    }

    public record LinkingReport(
            boolean enabled,
            int searchedChunks,
            int seededEdges,
            int selfRejected,
            int languageRejected,
            int duplicateRejected,
            int budgetRejected
    ) {
        public static LinkingReport disabled() {
            return new LinkingReport(false, 0, 0, 0, 0, 0, 0);
        }

        public static LinkingReport empty() {
            return new LinkingReport(true, 0, 0, 0, 0, 0, 0);
        }
    }
}
