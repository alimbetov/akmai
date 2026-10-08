package kz.alimbetov.akmai.knowledge.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Single-round-trip semantic pair admission snapshot. Callers are expected to
 * hold lifecycle and graph-node locks before using the result for mutation.
 */
@Repository
public class SemanticPairAdmissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SemanticPairAdmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PairAdmissionStats load(
            ChunkGraphNode first,
            ChunkGraphNode second,
            int graphVersion
    ) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("semantic pair nodes must not be null");
        }
        if (first.accessLevel() != second.accessLevel()) {
            throw new IllegalArgumentException("semantic pair must stay within one ACL");
        }
        if (graphVersion <= 0) {
            throw new IllegalArgumentException("graphVersion must be positive");
        }
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND graph_version = ?
                          AND (
                              (
                                  source_document_id = ?
                                  AND source_generation = ?
                                  AND source_chunk_id = ?
                                  AND target_document_id = ?
                                  AND target_generation = ?
                                  AND target_chunk_id = ?
                              )
                              OR
                              (
                                  source_document_id = ?
                                  AND source_generation = ?
                                  AND source_chunk_id = ?
                                  AND target_document_id = ?
                                  AND target_generation = ?
                                  AND target_chunk_id = ?
                              )
                          )
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
                (rs, rowNum) -> new PairAdmissionStats(
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
                second.documentId(),
                second.generation(),
                second.chunkId(),
                first.documentId(),
                first.generation(),
                first.chunkId(),
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

    public record PairAdmissionStats(
            boolean existingPair,
            int firstDegree,
            int secondDegree
    ) {
    }
}
