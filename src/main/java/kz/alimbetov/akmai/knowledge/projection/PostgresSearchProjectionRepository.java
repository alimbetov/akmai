package kz.alimbetov.akmai.knowledge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        if (projections == null || projections.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO knowledge_search_projection (
                    chunk_id, document_id, generation, parent_chunk_id, chunk_index,
                    text_content, embedding_text, language, domain, section_path,
                    identifiers_json, references_json, metadata_json, projection_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (document_id, generation, chunk_id) DO UPDATE SET
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
                    updated_at = clock_timestamp()
                """,
                projections,
                100,
                (ps, p) -> {
                    ps.setString(1, p.chunkId());
                    ps.setString(2, p.documentId());
                    ps.setLong(3, p.generation());
                    ps.setString(4, p.parentChunkId());
                    ps.setInt(5, p.chunkIndex());
                    ps.setString(6, p.text());
                    ps.setString(7, p.embeddingText());
                    ps.setString(8, p.language());
                    ps.setString(9, p.domain().name());
                    ps.setString(10, p.sectionPath());
                    ps.setString(11, writeJson(p.identifiers()));
                    ps.setString(12, writeJson(p.references()));
                    ps.setString(13, writeJson(p.metadata()));
                    ps.setInt(14, p.projectionVersion());
                }
        );
    }

    @Override
    public List<String> findChunkIdsByDocumentId(String documentId) {
        return jdbcTemplate.queryForList(
                """
                SELECT p.chunk_id
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE p.document_id = ?
                  AND l.retention_status = 'ACTIVE'
                ORDER BY p.chunk_index
                """,
                String.class,
                documentId
        );
    }

    @Override
    public List<String> findChunkIds(String documentId, long generation) {
        return jdbcTemplate.queryForList(
                """
                SELECT chunk_id
                FROM knowledge_search_projection
                WHERE document_id = ?
                  AND generation = ?
                ORDER BY chunk_index
                """,
                String.class,
                documentId,
                generation
        );
    }

    @Override
    public List<SearchProjection> findGeneration(
            String documentId,
            long generation
    ) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_search_projection
                WHERE document_id = ?
                  AND generation = ?
                ORDER BY chunk_index
                """,
                this::map,
                documentId,
                generation
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
    public void deleteGeneration(String documentId, long generation) {
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_search_projection
                WHERE document_id = ?
                  AND generation = ?
                """,
                documentId,
                generation
        );
    }

    @Override
    public List<SearchProjection> findByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT p.*
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE p.chunk_id = ANY (?)
                  AND l.retention_status = 'ACTIVE'
                ORDER BY p.document_id, p.chunk_index
                """,
                ps -> bindArray(ps, 1, chunkIds),
                this::map
        );
    }

    @Override
    public List<SearchProjection> findByDocumentAndChunkIds(
            String documentId,
            List<String> chunkIds
    ) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT p.*
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE p.document_id = ?
                  AND p.chunk_id = ANY (?)
                  AND l.retention_status = 'ACTIVE'
                ORDER BY p.chunk_index
                """,
                ps -> {
                    ps.setString(1, documentId);
                    bindArray(ps, 2, chunkIds);
                },
                this::map
        );
    }

    @Override
    public List<SearchProjection> findByDocumentAndChunkIds(
            String documentId,
            List<String> chunkIds,
            Set<Long> accessLevels
    ) {
        if (chunkIds == null
                || chunkIds.isEmpty()
                || accessLevels == null
                || accessLevels.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT p.*
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE p.document_id = ?
                  AND p.chunk_id = ANY (?)
                  AND l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                ORDER BY p.chunk_index
                """,
                ps -> {
                    ps.setString(1, documentId);
                    bindArray(ps, 2, chunkIds);
                    bindLongArray(ps, 3, accessLevels);
                },
                this::map
        );
    }

    @Override
    public List<SearchProjection> findByDocumentGenerationAndChunkIds(
            String documentId,
            long generation,
            List<String> chunkIds,
            Set<Long> accessLevels
    ) {
        if (generation <= 0
                || chunkIds == null
                || chunkIds.isEmpty()
                || accessLevels == null
                || accessLevels.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT p.*
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE p.document_id = ?
                  AND p.generation = ?
                  AND p.chunk_id = ANY (?)
                  AND l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                ORDER BY p.chunk_index
                """,
                ps -> {
                    ps.setString(1, documentId);
                    ps.setLong(2, generation);
                    bindArray(ps, 3, chunkIds);
                    bindLongArray(ps, 4, accessLevels);
                },
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
                SELECT p.*
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE p.document_id = ?
                  AND l.retention_status = 'ACTIVE'
                  AND p.chunk_index BETWEEN ? AND ?
                ORDER BY p.chunk_index
                """,
                this::map,
                documentId,
                Math.max(0, chunkIndex - radius),
                chunkIndex + radius
        );
    }

    @Override
    public List<SearchProjection> findAdjacent(
            String documentId,
            long generation,
            int chunkIndex,
            int radius,
            Set<Long> accessLevels
    ) {
        if (generation <= 0
                || accessLevels == null
                || accessLevels.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT p.*
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE p.document_id = ?
                  AND p.generation = ?
                  AND l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                  AND p.chunk_index BETWEEN ? AND ?
                ORDER BY p.chunk_index
                """,
                ps -> {
                    ps.setString(1, documentId);
                    ps.setLong(2, generation);
                    bindLongArray(ps, 3, accessLevels);
                    ps.setInt(4, Math.max(0, chunkIndex - radius));
                    ps.setInt(5, chunkIndex + radius);
                },
                this::map
        );
    }

    @Override
    public List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            int limit
    ) {
        return searchLexical(
                query,
                language,
                documentIds,
                Set.of(),
                limit
        );
    }

    @Override
    public List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if (query == null
                || query.isBlank()
                || limit <= 0
                || accessLevels == null
                || accessLevels.isEmpty()) {
            return List.of();
        }
        LexicalSearchLanguage searchLanguage =
                LexicalSearchLanguage.from(language);
        return switch (searchLanguage) {
            case RU -> searchFtsWithLanguageFallback(
                    query,
                    "ru",
                    documentIds,
                    accessLevels,
                    limit,
                    "search_vector_ru",
                    "russian"
            );
            case EN -> searchFtsWithLanguageFallback(
                    query,
                    "en",
                    documentIds,
                    accessLevels,
                    limit,
                    "search_vector_en",
                    "english"
            );
            case KK -> searchTrigram(
                    query,
                    "kk",
                    documentIds,
                    accessLevels,
                    limit
            );
            case ZH -> searchTrigram(
                    query,
                    "zh",
                    documentIds,
                    accessLevels,
                    limit
            );
            case UNKNOWN -> searchSimple(
                    query,
                    documentIds,
                    accessLevels,
                    limit
            );
        };
    }

    private List<SearchProjection> searchFtsWithLanguageFallback(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit,
            String vectorColumn,
            String configuration
    ) {
        List<SearchProjection> fts = searchFts(
                query,
                language,
                documentIds,
                accessLevels,
                limit,
                vectorColumn,
                configuration
        );
        if (!fts.isEmpty()) {
            return fts;
        }
        return searchLanguageTrigramFallback(
                query,
                language,
                documentIds,
                accessLevels,
                limit
        );
    }

    private List<SearchProjection> searchLanguageTrigramFallback(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        List<String> terms = java.util.Arrays.stream(
                        query.toLowerCase(java.util.Locale.ROOT).split("\\s+")
                )
                .map(term -> term.replaceAll(
                        "(?U)^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$",
                        ""
                ))
                .filter(term -> term.length() >= 3)
                .distinct()
                .limit(8)
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }

        String score = terms.stream()
                .map(ignored -> """
                        greatest(
                            word_similarity(lower(?), lower(p.text_content)),
                            word_similarity(
                                lower(?),
                                lower(coalesce(p.section_path, ''))
                            )
                        )
                        """.strip())
                .collect(java.util.stream.Collectors.joining(" + "));

        String predicate = terms.stream()
                .map(ignored -> """
                        greatest(
                            word_similarity(lower(?), lower(p.text_content)),
                            word_similarity(
                                lower(?),
                                lower(coalesce(p.section_path, ''))
                            )
                        ) >= 0.45
                        """.strip())
                .collect(java.util.stream.Collectors.joining(" AND "));

        String sql = """
                SELECT p.*,
                       (%s) / %d AS lexical_rank
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                  AND p.language = ?
                  AND (%s)
                """.formatted(score, terms.size(), predicate)
                + documentFilter(documentIds)
                + """
                ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    for (String term : terms) {
                        ps.setString(i++, term);
                        ps.setString(i++, term);
                    }
                    bindLongArray(ps, i++, accessLevels);
                    ps.setString(i++, language);
                    for (String term : terms) {
                        ps.setString(i++, term);
                        ps.setString(i++, term);
                    }
                    i = bindDocumentIds(ps, i, documentIds);
                    ps.setInt(i, limit);
                },
                this::map
        );
    }

    private List<SearchProjection> searchFts(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit,
            String vectorColumn,
            String configuration
    ) {
        String sql = """
                SELECT p.*,
                       ts_rank(p.%s, websearch_to_tsquery('%s', ?)) AS lexical_rank
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                  AND p.language = ?
                  AND p.%s @@ websearch_to_tsquery('%s', ?)
                """.formatted(vectorColumn, configuration, vectorColumn, configuration)
                + documentFilter(documentIds)
                + """
                ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    ps.setString(i++, query);
                    bindLongArray(ps, i++, accessLevels);
                    ps.setString(i++, language);
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
            Set<Long> accessLevels,
            int limit
    ) {
        String escaped = escapeLikeLiteral(query);
        String sql = """
                SELECT p.*,
                       greatest(
                           similarity(lower(p.text_content), lower(?)),
                           similarity(lower(coalesce(p.section_path, '')), lower(?))
                       ) AS lexical_rank
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                  AND p.language = ?
                  AND (
                      lower(p.text_content) % lower(?)
                      OR lower(coalesce(p.section_path, '')) % lower(?)
                      OR lower(p.text_content) LIKE ('%' || lower(?) || '%') ESCAPE '\\'
                  )
                """ + documentFilter(documentIds) + """
                ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    ps.setString(i++, query);
                    ps.setString(i++, query);
                    bindLongArray(ps, i++, accessLevels);
                    ps.setString(i++, language);
                    ps.setString(i++, query);
                    ps.setString(i++, query);
                    ps.setString(i++, escaped);
                    i = bindDocumentIds(ps, i, documentIds);
                    ps.setInt(i, limit);
                },
                this::map
        );
    }

    private List<SearchProjection> searchSimple(
            String query,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        String sql = """
                SELECT p.*,
                       ts_rank(p.search_vector, websearch_to_tsquery('simple', ?)) AS lexical_rank
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                  AND p.search_vector @@ websearch_to_tsquery('simple', ?)
                """ + documentFilter(documentIds) + """
                ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    ps.setString(i++, query);
                    bindLongArray(ps, i++, accessLevels);
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
                : " AND p.document_id = ANY (?) ";
    }

    private int bindDocumentIds(
            PreparedStatement ps,
            int index,
            List<String> documentIds
    ) throws SQLException {
        if (documentIds != null && !documentIds.isEmpty()) {
            bindArray(ps, index++, documentIds);
        }
        return index;
    }

    private void bindLongArray(
            PreparedStatement ps,
            int index,
            Set<Long> values
    ) throws SQLException {
        ps.setArray(
                index,
                ps.getConnection().createArrayOf(
                        "bigint",
                        values.toArray()
                )
        );
    }

    private void bindArray(
            PreparedStatement ps,
            int index,
            List<String> values
    ) throws SQLException {
        ps.setArray(
                index,
                ps.getConnection().createArrayOf("varchar", values.toArray())
        );
    }

    private String escapeLikeLiteral(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private SearchProjection map(ResultSet rs, int rowNum) throws SQLException {
        return new SearchProjection(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getLong("generation"),
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
