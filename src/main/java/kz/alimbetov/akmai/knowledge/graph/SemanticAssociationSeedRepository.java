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
    private final GraphMutationLocks mutationLocks;

    public SemanticAssociationSeedRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.mutationLocks = new GraphMutationLocks(jdbcTemplate);
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

        Boolean seeded = transactionTemplate.execute(status -> {
            mutationLocks.lockEligiblePublishedNodes(List.of(first, second));

            PairState state = pairState(first, second, graphVersion);
            if (!state.existingPair()
                    && (state.firstDegree() >= maxSemanticDegree
                    || state.secondDegree() >= maxSemanticDegree)) {
                return false;
            }

            upsertPair(first, second, similarity, graphVersion, observedAt);
            return true;
        });
        return Boolean.TRUE.equals(seeded);
    }

    private PairState pairState(
            ChunkGraphNode first,
            ChunkGraphNode second,
            int graphVersion
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND graph_version = ?
                          AND source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND target_document_id = ?
                          AND target_generation = ?
                          AND target_chunk_id = ?
                    ) AS existing_pair,
                    (
                        SELECT count(*)
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND graph_version = ?
                          AND semantic_similarity IS NOT NULL
                          AND band <> 'DECAYED'
                    ) AS first_degree,
                    (
                        SELECT count(*)
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND graph_version = ?
                          AND semantic_similarity IS NOT NULL
                          AND band <> 'DECAYED'
                    ) AS second_degree
                """,
                (rs, rowNum) -> new PairState(
                        rs.getBoolean("existing_pair"),
                        rs.getInt("first_degree"),
                        rs.getInt("second_degree")
                ),
                first.accessLevel(),
                graphVersion,
                first.documentId(),
                first.generation(),
                first.chunkId(),
                second.documentId(),
                second.generation(),
                second.chunkId(),
                first.accessLevel(),
                first.documentId(),
                first.generation(),
                first.chunkId(),
                graphVersion,
                second.accessLevel(),
                second.documentId(),
                second.generation(),
                second.chunkId(),
                graphVersion
        );
    }

    private void upsertPair(
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

    private record PairState(
            boolean existingPair,
            int firstDegree,
            int secondDegree
    ) {
    }
}
