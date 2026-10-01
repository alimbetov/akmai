package kz.alimbetov.akmai.knowledge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSearchProjectionRepository implements SearchProjectionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresSearchProjectionRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveAll(List<SearchProjection> projections) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO knowledge_search_projection (
                    chunk_id, document_id, parent_chunk_id, chunk_index,
                    text_content, embedding_text, language, domain, section_path,
                    references_json, metadata_json, projection_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    document_id = EXCLUDED.document_id,
                    parent_chunk_id = EXCLUDED.parent_chunk_id,
                    chunk_index = EXCLUDED.chunk_index,
                    text_content = EXCLUDED.text_content,
                    embedding_text = EXCLUDED.embedding_text,
                    language = EXCLUDED.language,
                    domain = EXCLUDED.domain,
                    section_path = EXCLUDED.section_path,
                    references_json = EXCLUDED.references_json,
                    metadata_json = EXCLUDED.metadata_json,
                    projection_version = EXCLUDED.projection_version,
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
                    ps.setString(6, p.embeddingText());
                    ps.setString(7, p.language());
                    ps.setString(8, p.domain().name());
                    ps.setString(9, p.sectionPath());
                    ps.setString(10, writeJson(p.references()));
                    ps.setString(11, writeJson(p.metadata()));
                    ps.setInt(12, p.projectionVersion());
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
                rs.getString("embedding_text"),
                rs.getString("language"),
                KnowledgeDomain.valueOf(rs.getString("domain")),
                rs.getString("section_path"),
                List.of(),
                readJson(rs.getString("references_json"), new TypeReference<List<String>>() {}),
                readJson(rs.getString("metadata_json"), new TypeReference<Map<String, Object>>() {}),
                rs.getInt("projection_version")
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize search projection JSON", exception);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) throws SQLException {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Cannot deserialize search projection JSON", exception);
        }
    }
}
