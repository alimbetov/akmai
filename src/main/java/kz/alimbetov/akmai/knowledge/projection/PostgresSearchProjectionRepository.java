package kz.alimbetov.akmai.knowledge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
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
                    identifiers_json, references_json, metadata_json, projection_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    document_id = EXCLUDED.document_id,
                    parent_chunk_id = EXCLUDED.parent_chunk_id,
                    chunk_index = EXCLUDED.chunk_index,
                    text_content = EXCLUDED.text_content,
                    embedding_text = EXCLUDED.embedding_text,
                    language = EXCLUDED.language,
                    domain = EXCLUDED.domain,
                    section_path = EXCLUDED.section_path,
                    identifiers_json = EXCLUDED.identifiers_json,
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
                    ps.setString(10, writeJson(p.identifiers()));
                    ps.setString(11, writeJson(p.references()));
                    ps.setString(12, writeJson(p.metadata()));
                    ps.setInt(13, p.projectionVersion());
                }
        );
    }

    @Override
    public List<String> findChunkIdsByDocumentId(String documentId) {
        return jdbcTemplate.queryForList(
                "SELECT chunk_id FROM knowledge_search_projection WHERE document_id = ?",
                String.class,
                documentId
        );
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        jdbcTemplate.update(
                "DELETE FROM knowledge_search_projection WHERE document_id = ?",
                documentId
        );
    }

    @Override
    public List<SearchProjection> findByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT * FROM knowledge_search_projection WHERE chunk_id = ANY (?)",
                ps -> ps.setArray(
                        1,
                        ps.getConnection().createArrayOf("varchar", chunkIds.toArray())
                ),
                this::map
        );
    }

    @Override
    public List<SearchProjection> findAdjacent(
            String documentId,
            int chunkIndex,
            int radius
    ) {
        return jdbcTemplate.query(
                """
                SELECT * FROM knowledge_search_projection
                WHERE document_id = ?
                  AND chunk_index BETWEEN ? AND ?
                ORDER BY chunk_index
                """,
                this::map,
                documentId,
                Math.max(0, chunkIndex - radius),
                chunkIndex + radius
        );
    }

    @Override
    public List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            int limit
    ) {
        LexicalSearchLanguage searchLanguage = LexicalSearchLanguage.from(language);
        return switch (searchLanguage) {
            case RU -> searchFts(query, language, documentIds, limit, "russian");
            case EN -> searchFts(query, language, documentIds, limit, "english");
            case KK, ZH -> searchTrigram(query, language, documentIds, limit);
            case UNKNOWN -> searchSimple(query, documentIds, limit);
        };
    }

    private List<SearchProjection> searchFts(
            String query,
            String language,
            List<String> documentIds,
            int limit,
            String configuration
    ) {
        String sql = """
                SELECT *,
                       ts_rank(
                           to_tsvector(?::regconfig, coalesce(section_path, '') || ' ' || text_content),
                           websearch_to_tsquery(?::regconfig, ?)
                       ) AS lexical_rank
                FROM knowledge_search_projection
                WHERE language = ?
                  AND to_tsvector(
                        ?::regconfig,
                        coalesce(section_path, '') || ' ' || text_content
                      ) @@ websearch_to_tsquery(?::regconfig, ?)
                """ + documentFilter(documentIds) + """
                ORDER BY lexical_rank DESC, chunk_id
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    ps.setString(i++, configuration);
                    ps.setString(i++, configuration);
                    ps.setString(i++, query);
                    ps.setString(i++, language);
                    ps.setString(i++, configuration);
                    ps.setString(i++, configuration);
                    ps.setString(i++, query);
                    i = bindDocumentIds(ps, i, documentIds);
                    ps.setInt(i, limit);
                },
                this::map
        );
    }

    private List<SearchProjection> searchTrigram(
            String query,
            String language,
            List<String> documentIds,
            int limit
    ) {
        String sql = """
                SELECT *,
                       greatest(
                           similarity(lower(text_content), lower(?)),
                           similarity(lower(coalesce(section_path, '')), lower(?))
                       ) AS lexical_rank
                FROM knowledge_search_projection
                WHERE language = ?
                  AND (
                      lower(text_content) % lower(?)
                      OR lower(coalesce(section_path, '')) % lower(?)
                      OR lower(text_content) LIKE '%' || lower(?) || '%'
                  )
                """ + documentFilter(documentIds) + """
                ORDER BY lexical_rank DESC, chunk_id
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    ps.setString(i++, query);
                    ps.setString(i++, query);
                    ps.setString(i++, language);
                    ps.setString(i++, query);
                    ps.setString(i++, query);
                    ps.setString(i++, query);
                    i = bindDocumentIds(ps, i, documentIds);
                    ps.setInt(i, limit);
                },
                this::map
        );
    }

    private List<SearchProjection> searchSimple(
            String query,
            List<String> documentIds,
            int limit
    ) {
        String sql = """
                SELECT *,
                       ts_rank(search_vector, websearch_to_tsquery('simple', ?)) AS lexical_rank
                FROM knowledge_search_projection
                WHERE search_vector @@ websearch_to_tsquery('simple', ?)
                """ + documentFilter(documentIds) + """
                ORDER BY lexical_rank DESC, chunk_id
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    ps.setString(i++, query);
                    ps.setString(i++, query);
                    i = bindDocumentIds(ps, i, documentIds);
                    ps.setInt(i, limit);
                },
                this::map
        );
    }

    private String documentFilter(List<String> documentIds) {
        return documentIds == null || documentIds.isEmpty()
                ? ""
                : " AND document_id = ANY (?) ";
    }

    private int bindDocumentIds(
            java.sql.PreparedStatement ps,
            int index,
            List<String> documentIds
    ) throws SQLException {
        if (documentIds != null && !documentIds.isEmpty()) {
            ps.setArray(
                    index++,
                    ps.getConnection().createArrayOf("varchar", documentIds.toArray())
            );
        }
        return index;
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
                readJson(rs.getString("identifiers_json"), new TypeReference<List<DetectedIdentifier>>() {}),
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
