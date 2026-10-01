package kz.alimbetov.akmai.knowledge.projection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSearchProjectionRepository implements SearchProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresSearchProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveAll(List<SearchProjection> projections) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO knowledge_search_projection (
                    chunk_id, document_id, parent_chunk_id, chunk_index,
                    text_content, language, section_path
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    document_id = EXCLUDED.document_id,
                    parent_chunk_id = EXCLUDED.parent_chunk_id,
                    chunk_index = EXCLUDED.chunk_index,
                    text_content = EXCLUDED.text_content,
                    language = EXCLUDED.language,
                    section_path = EXCLUDED.section_path,
                    updated_at = now()
                """,
                projections,
                100,
                (ps, p) -> {
                    ps.setString(1, p.chunkId());
                    ps.setString(2, p.documentId());
                    ps.setString(3, p.parentChunkId());
                    ps.setInt(4, p.chunkIndex());
                    ps.setString(5, p.text());
                    ps.setString(6, p.language());
                    ps.setString(7, p.sectionPath());
                }
        );
    }

    @Override
    public List<SearchProjection> searchLexical(
            String query,
            List<String> documentIds,
            int limit
    ) {
        if (documentIds == null || documentIds.isEmpty()) {
            return jdbcTemplate.query(
                    """
                    SELECT * FROM knowledge_search_projection
                    WHERE search_vector @@ websearch_to_tsquery('simple', ?)
                    ORDER BY ts_rank(search_vector, websearch_to_tsquery('simple', ?)) DESC
                    LIMIT ?
                    """,
                    this::map,
                    query,
                    query,
                    limit
            );
        }

        return jdbcTemplate.query(
                """
                SELECT * FROM knowledge_search_projection
                WHERE search_vector @@ websearch_to_tsquery('simple', ?)
                  AND document_id = ANY (?)
                ORDER BY ts_rank(search_vector, websearch_to_tsquery('simple', ?)) DESC
                LIMIT ?
                """,
                ps -> {
                    ps.setString(1, query);
                    ps.setArray(2, ps.getConnection().createArrayOf(
                            "varchar", documentIds.toArray()
                    ));
                    ps.setString(3, query);
                    ps.setInt(4, limit);
                },
                this::map
        );
    }

    private SearchProjection map(ResultSet rs, int rowNum) throws SQLException {
        return new SearchProjection(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("parent_chunk_id"),
                rs.getInt("chunk_index"),
                rs.getString("text_content"),
                rs.getString("text_content"),
                rs.getString("language"),
                rs.getString("section_path"),
                List.of(),
                List.of(),
                Map.of(
                        "source", rs.getString("document_id"),
                        "language", rs.getString("language"),
                        "sectionPath", rs.getString("section_path")
                )
        );
    }
}
