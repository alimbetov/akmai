package kz.alimbetov.akmai.knowledge.graph;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SemanticAssociationSeedRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final GraphLifecycleGuard lifecycleGuard;
    private final GraphNodeLockManager graphNodeLockManager;
    private final SemanticGraphStateRepository graphStateRepository;

    public SemanticAssociationSeedRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            GraphLifecycleGuard lifecycleGuard,
            GraphNodeLockManager graphNodeLockManager,
            SemanticGraphStateRepository graphStateRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.lifecycleGuard = lifecycleGuard;
        this.graphNodeLockManager = graphNodeLockManager;
        this.graphStateRepository = graphStateRepository;
    }

    public boolean seedSymmetric(
            ChunkGraphNode left,
            ChunkGraphNode right,
            double similarity,
            int graphVersion,
            int maxSemanticDegree,
            Instant observedAt
    ) {
        GraphPairCanonicalizer.CanonicalPair pair =
                GraphPairCanonicalizer.canonicalize(left, right);
        if (!Double.isFinite(similarity)
                || similarity < 0
                || similarity > 1) {
            throw new IllegalArgumentException("similarity must be in [0, 1]");
        }
        if (graphVersion <= 0) {
            throw new IllegalArgumentException("graphVersion must be positive");
        }
        if (maxSemanticDegree < 1) {
            throw new IllegalArgumentException("maxSemanticDegree must be positive");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }

        List<ChunkGraphNode> nodes = List.of(pair.first(), pair.second());
        Boolean seeded = transactionTemplate.execute(status -> {
            lifecycleGuard.lockSemanticEligibleCanonical(nodes);
            graphNodeLockManager.lockCanonical(nodes);

            SemanticGraphStateRepository.PairState state = graphStateRepository.inspect(
                    pair.first(),
                    pair.second(),
                    graphVersion
            );
            if (!state.degreeAvailable(maxSemanticDegree)) {
                return false;
            }

            upsertSymmetric(
                    pair.first(),
                    pair.second(),
                    similarity,
                    graphVersion,
                    observedAt
            );
            return true;
        });
        return Boolean.TRUE.equals(seeded);
    }

    private void upsertSymmetric(
            ChunkGraphNode first,
            ChunkGraphNode second,
            double similarity,
            int graphVersion,
            Instant observedAt
    ) {
        Timestamp observed = Timestamp.from(observedAt);
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_chunk_association (
                    access_level,
                    source_document_id,
                    source_generation,
                    source_chunk_id,
                    target_document_id,
                    target_generation,
                    target_chunk_id,
                    band,
                    weight,
                    semantic_similarity,
                    semantic_seeded_at,
                    semantic_last_seen_at,
                    support_count,
                    context_count,
                    citation_count,
                    query_support_sketch,
                    distinct_query_support,
                    graph_version,
                    first_seen_at,
                    last_seen_at,
                    last_reinforced_at,
                    updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    'CANDIDATE', 0, ?, ?, ?,
                    0, 0, 0, 0::bit(256), 0, ?, ?, ?, ?, clock_timestamp()
                ), (
                    ?, ?, ?, ?, ?, ?, ?,
                    'CANDIDATE', 0, ?, ?, ?,
                    0, 0, 0, 0::bit(256), 0, ?, ?, ?, ?, clock_timestamp()
                )
                ON CONFLICT (
                    access_level,
                    source_document_id,
                    source_generation,
                    source_chunk_id,
                    target_document_id,
                    target_generation,
                    target_chunk_id,
                    graph_version
                ) DO UPDATE SET
                    semantic_similarity = greatest(
                        coalesce(knowledge_chunk_association.semantic_similarity, 0),
                        EXCLUDED.semantic_similarity
                    ),
                    semantic_seeded_at = coalesce(
                        knowledge_chunk_association.semantic_seeded_at,
                        EXCLUDED.semantic_seeded_at
                    ),
                    semantic_last_seen_at = greatest(
                        coalesce(
                            knowledge_chunk_association.semantic_last_seen_at,
                            EXCLUDED.semantic_last_seen_at
                        ),
                        EXCLUDED.semantic_last_seen_at
                    ),
                    compaction_required = TRUE,
                    updated_at = clock_timestamp()
                """,
                first.accessLevel(),
                first.documentId(),
                first.generation(),
                first.chunkId(),
                second.documentId(),
                second.generation(),
                second.chunkId(),
                similarity,
                observed,
                observed,
                graphVersion,
                observed,
                observed,
                observed,
                second.accessLevel(),
                second.documentId(),
                second.generation(),
                second.chunkId(),
                first.documentId(),
                first.generation(),
                first.chunkId(),
                similarity,
                observed,
                observed,
                graphVersion,
                observed,
                observed,
                observed
        );
    }
}
