package kz.alimbetov.akmai.knowledge.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-side state probe used by semantic graph mutation paths while endpoint
 * lifecycle/advisory locks are already held by the caller.
 */
@Repository
public class SemanticGraphStateRepository {

    private final JdbcTemplate jdbcTemplate;

    public SemanticGraphStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PairState inspect(
            ChunkGraphNode left,
            ChunkGraphNode right,
            int graphVersion
    ) {
        GraphPairCanonicalizer.CanonicalPair pair =
                GraphPairCanonicalizer.canonicalize(left, right);
        if (graphVersion <= 0) {
            throw new IllegalArgumentException("graphVersion must be positive");
        }

        PairState state = jdbcTemplate.queryForObject(
                """
                WITH relevant AS (
                    SELECT source_document_id,
                           source_generation,
                           source_chunk_id,
                           target_document_id,
                           target_generation,
                           target_chunk_id,
                           semantic_similarity,
                           band
                    FROM knowledge_chunk_association
                    WHERE access_level = ?
                      AND graph_version = ?
                      AND (
                          (source_document_id = ?
                           AND source_generation = ?
                           AND source_chunk_id = ?)
                          OR
                          (source_document_id = ?
                           AND source_generation = ?
                           AND source_chunk_id = ?)
                      )
                )
                SELECT
                    coalesce(bool_or(
                        (source_document_id = ?
                         AND source_generation = ?
                         AND source_chunk_id = ?
                         AND target_document_id = ?
                         AND target_generation = ?
                         AND target_chunk_id = ?)
                        OR
                        (source_document_id = ?
                         AND source_generation = ?
                         AND source_chunk_id = ?
                         AND target_document_id = ?
                         AND target_generation = ?
                         AND target_chunk_id = ?)
                    ), false) AS pair_exists,
                    count(*) FILTER (
                        WHERE source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND semantic_similarity IS NOT NULL
                          AND band <> 'DECAYED'
                    ) AS first_degree,
                    count(*) FILTER (
                        WHERE source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND semantic_similarity IS NOT NULL
                          AND band <> 'DECAYED'
                    ) AS second_degree
                FROM relevant
                """,
                (rs, rowNum) -> new PairState(
                        rs.getBoolean("pair_exists"),
                        rs.getInt("first_degree"),
                        rs.getInt("second_degree")
                ),
                pair.first().accessLevel(),
                graphVersion,
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId()
        );
        if (state == null) {
            throw new IllegalStateException("semantic graph state probe returned no row");
        }
        return state;
    }

    public record PairState(
            boolean pairExists,
            int firstDegree,
            int secondDegree
    ) {
        public boolean degreeAvailable(int maxSemanticDegree) {
            if (maxSemanticDegree < 1) {
                throw new IllegalArgumentException("maxSemanticDegree must be positive");
            }
            return pairExists
                    || (firstDegree < maxSemanticDegree
                    && secondDegree < maxSemanticDegree);
        }
    }
}
