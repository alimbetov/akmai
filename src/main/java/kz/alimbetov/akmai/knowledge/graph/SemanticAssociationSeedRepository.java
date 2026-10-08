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
    private final GraphNodeLockManager graphNodeLockManager;
    private final GraphLifecycleGuard graphLifecycleGuard;
    private final SemanticPairAdmissionRepository pairAdmissionRepository;

    public SemanticAssociationSeedRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            GraphNodeLockManager graphNodeLockManager,
            GraphLifecycleGuard graphLifecycleGuard,
            SemanticPairAdmissionRepository pairAdmissionRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.graphNodeLockManager = graphNodeLockManager;
        this.graphLifecycleGuard = graphLifecycleGuard;
        this.pairAdmissionRepository = pairAdmissionRepository;
    }

    public boolean seedSymmetric(
            ChunkGraphNode left,
            ChunkGraphNode right,
            double similarity,
            int graphVersion,
            int maxSemanticDegree,
            Instant observedAt
    ) {
        requirePair(left, right);
        if (!Double.isFinite(similarity)
                || similarity < 0
                || similarity > 1) {
            throw new IllegalArgumentException(
                    "similarity must be in [0, 1]"
            );
        }
        if (graphVersion <= 0) {
            throw new IllegalArgumentException("graphVersion must be positive");
        }
        if (maxSemanticDegree < 1) {
            throw new IllegalArgumentException(
                    "maxSemanticDegree must be positive"
            );
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }

        ChunkGraphNode first = left.compareTo(right) <= 0 ? left : right;
        ChunkGraphNode second = first == left ? right : left;
        List<ChunkGraphNode> nodes = List.of(first, second);

        Boolean seeded = transactionTemplate.execute(status -> {
            nodes.forEach(graphLifecycleGuard::lockPublishedReadyActive);
            graphNodeLockManager.lockCanonical(nodes);

            SemanticPairAdmissionRepository.PairAdmissionStats stats =
                    pairAdmissionRepository.load(first, second, graphVersion);
            if (!stats.existingPair()
                    && (stats.firstDegree() >= maxSemanticDegree
                    || stats.secondDegree() >= maxSemanticDegree)) {
                return false;
            }

            upsertDirection(first, second, similarity, graphVersion, observedAt);
            upsertDirection(second, first, similarity, graphVersion, observedAt);
            return true;
        });
        return Boolean.TRUE.equals(seeded);
    }

    private void upsertDirection(
            ChunkGraphNode source,
            ChunkGraphNode target,
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
                source.accessLevel(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                target.documentId(),
                target.generation(),
                target.chunkId(),
                similarity,
                observed,
                observed,
                graphVersion,
                observed,
                observed,
                observed
        );
    }

    private void requirePair(ChunkGraphNode left, ChunkGraphNode right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException(
                    "semantic association nodes must not be null"
            );
        }
        if (left.equals(right)) {
            throw new IllegalArgumentException(
                    "self semantic association is not allowed"
            );
        }
        if (left.accessLevel() != right.accessLevel()) {
            throw new IllegalArgumentException(
                    "cross-ACL semantic association is forbidden"
            );
        }
    }
}
