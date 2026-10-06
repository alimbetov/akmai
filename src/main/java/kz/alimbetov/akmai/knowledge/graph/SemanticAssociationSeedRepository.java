package kz.alimbetov.akmai.knowledge.graph;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SemanticAssociationSeedRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SemanticAssociationSeedRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
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
        TreeSet<ChunkGraphNode> lockOrder = new TreeSet<>();
        lockOrder.add(first);
        lockOrder.add(second);

        Boolean seeded = transactionTemplate.execute(status -> {
            lockOrder.forEach(this::lockPublishedGeneration);
            lockOrder.forEach(this::lockNode);

            boolean existing = associationExists(
                    first,
                    second,
                    graphVersion
            );
            if (!existing
                    && (semanticDegree(first, graphVersion) >= maxSemanticDegree
                    || semanticDegree(second, graphVersion) >= maxSemanticDegree)) {
                return false;
            }

            upsertDirection(
                    first,
                    second,
                    similarity,
                    graphVersion,
                    observedAt
            );
            upsertDirection(
                    second,
                    first,
                    similarity,
                    graphVersion,
                    observedAt
            );
            return true;
        });
        return Boolean.TRUE.equals(seeded);
    }

    private boolean associationExists(
            ChunkGraphNode source,
            ChunkGraphNode target,
            int graphVersion
    ) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM knowledge_chunk_association
                    WHERE access_level = ?
                      AND source_document_id = ?
                      AND source_generation = ?
                      AND source_chunk_id = ?
                      AND target_document_id = ?
                      AND target_generation = ?
                      AND target_chunk_id = ?
                      AND graph_version = ?
                )
                """,
                Boolean.class,
                source.accessLevel(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                target.documentId(),
                target.generation(),
                target.chunkId(),
                graphVersion
        );
        return Boolean.TRUE.equals(exists);
    }

    private int semanticDegree(
            ChunkGraphNode node,
            int graphVersion
    ) {
        Integer degree = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE access_level = ?
                  AND source_document_id = ?
                  AND source_generation = ?
                  AND source_chunk_id = ?
                  AND graph_version = ?
                  AND semantic_similarity IS NOT NULL
                  AND band <> 'DECAYED'
                """,
                Integer.class,
                node.accessLevel(),
                node.documentId(),
                node.generation(),
                node.chunkId(),
                graphVersion
        );
        return degree == null ? 0 : degree;
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
                        coalesce(
                            knowledge_chunk_association.semantic_similarity,
                            0
                        ),
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

    private void lockPublishedGeneration(ChunkGraphNode node) {
        Integer published = jdbcTemplate.query(
                """
                SELECT 1
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND access_level = ?
                  AND published_generation = ?
                  AND lifecycle_status = 'READY'
                  AND retention_status = 'ACTIVE'
                FOR SHARE
                """,
                (rs, rowNum) -> rs.getInt(1),
                node.documentId(),
                node.accessLevel(),
                node.generation()
        ).stream().findFirst().orElse(null);

        if (published == null) {
            throw new IllegalStateException(
                    "semantic linking requires ACTIVE/PUBLISHED generation"
            );
        }
    }

    private void lockNode(ChunkGraphNode node) {
        jdbcTemplate.query(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)
                )
                """,
                rs -> {
                },
                "akmai:adaptive-graph:node:" + node.lockKey()
        );
    }
}
