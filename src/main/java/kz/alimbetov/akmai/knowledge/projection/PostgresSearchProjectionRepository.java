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
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSearchProjectionRepository
        implements SearchProjectionRepository, PublishedSearchProjectionReader {

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

        Map<GenerationKey, List<SearchProjection>> grouped =
                projections.stream().collect(
                        java.util.stream.Collectors.groupingBy(
                                value -> new GenerationKey(
                                        value.documentId(),
                                        value.generation()
                                ),
                                java.util.LinkedHashMap::new,
                                java.util.stream.Collectors.toList()
                        )
                );
        grouped.forEach((key, values) ->
                saveAll(
                        identityFor(
                                key.documentId(),
                                key.generation()
                        ),
                        values
                )
        );
    }

    @Override
    public void saveAll(
            GenerationIdentity identity,
            List<SearchProjection> projections
    ) {
        requireGenerationIdentity(identity, projections);
        if (projections == null || projections.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                """
                INSERT INTO knowledge_search_projection (
                    access_level,
                    chunk_id,
                    document_id,
                    generation,
                    parent_chunk_id,
                    chunk_index,
                    text_content,
                    embedding_text,
                    language,
                    domain,
                    section_path,
                    identifiers_json,
                    references_json,
                    metadata_json,
                    projection_version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?::jsonb, ?::jsonb, ?::jsonb, ?
                )
                ON CONFLICT (
                    access_level,
                    document_id,
                    generation,
                    chunk_id
                ) DO UPDATE SET
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
                (ps, projection) -> {
                    ps.setLong(1, identity.accessLevel());
                    ps.setString(2, projection.chunkId());
                    ps.setString(3, identity.documentId());
                    ps.setLong(4, identity.generation());
                    ps.setString(5, projection.parentChunkId());
                    ps.setInt(6, projection.chunkIndex());
                    ps.setString(7, projection.text());
                    ps.setString(8, projection.embeddingText());
                    ps.setString(9, projection.language());
                    ps.setString(10, projection.domain().name());
                    ps.setString(11, projection.sectionPath());
                    ps.setString(12, writeJson(projection.identifiers()));
                    ps.setString(13, writeJson(projection.references()));
                    ps.setString(14, writeJson(projection.metadata()));
                    ps.setInt(15, projection.projectionVersion());
                }
        );
    }

    @Override
    public List<String> findChunkIds(String documentId, long generation) {
        GenerationIdentity identity = identityFor(documentId, generation);
        return jdbcTemplate.queryForList(
                """
                SELECT chunk_id
                FROM knowledge_search_projection
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                ORDER BY chunk_index
                """,
                String.class,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
    }

    @Override
    public List<SearchProjection> findGeneration(
            String documentId,
            long generation
    ) {
        return findGeneration(
                identityFor(documentId, generation)
        );
    }

    @Override
    public List<SearchProjection> findGeneration(
            GenerationIdentity identity
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_search_projection
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                ORDER BY chunk_index
                """,
                this::map,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
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
        deleteGeneration(identityFor(documentId, generation));
    }

    @Override
    public void deleteGeneration(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_search_projection
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
    }

    @Override
    public List<SearchProjection> findByDocumentAndChunkIds(
            String documentId,
            List<String> chunkIds,
            Set<Long> accessLevels
    ) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }

        List<SearchProjection> result = new java.util.ArrayList<>();
        for (long accessLevel : routedAccessLevels(accessLevels)) {
            result.addAll(jdbcTemplate.query(
                    """
                    SELECT p.*
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND p.document_id = ?
                      AND p.chunk_id = ANY (?)
                      AND l.retention_status = 'ACTIVE'
                    ORDER BY p.chunk_index
                    """,
                    ps -> {
                        ps.setLong(1, accessLevel);
                        ps.setString(2, documentId);
                        bindArray(ps, 3, chunkIds);
                    },
                    this::map
            ));
        }

        return result.stream()
                .sorted(java.util.Comparator.comparingInt(
                        SearchProjection::chunkIndex
                ))
                .toList();
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
                || chunkIds.isEmpty()) {
            return List.of();
        }

        List<SearchProjection> result = new java.util.ArrayList<>();
        for (long accessLevel : routedAccessLevels(accessLevels)) {
            result.addAll(jdbcTemplate.query(
                    """
                    SELECT p.*
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND p.document_id = ?
                      AND p.generation = ?
                      AND p.chunk_id = ANY (?)
                      AND l.retention_status = 'ACTIVE'
                    ORDER BY p.chunk_index
                    """,
                    ps -> {
                        ps.setLong(1, accessLevel);
                        ps.setString(2, documentId);
                        ps.setLong(3, generation);
                        bindArray(ps, 4, chunkIds);
                    },
                    this::map
            ));
        }

        return result.stream()
                .sorted(java.util.Comparator.comparingInt(
                        SearchProjection::chunkIndex
                ))
                .toList();
    }

    @Override
    public List<SearchProjection> findAdjacent(
            String documentId,
            long generation,
            int chunkIndex,
            int radius,
            Set<Long> accessLevels
    ) {
        if (generation <= 0) {
            return List.of();
        }

        List<SearchProjection> result = new java.util.ArrayList<>();
        int from = Math.max(0, chunkIndex - radius);
        int to = chunkIndex + radius;

        for (long accessLevel : routedAccessLevels(accessLevels)) {
            result.addAll(jdbcTemplate.query(
                    """
                    SELECT p.*
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND p.document_id = ?
                      AND p.generation = ?
                      AND l.retention_status = 'ACTIVE'
                      AND p.chunk_index BETWEEN ? AND ?
                    ORDER BY p.chunk_index
                    """,
                    this::map,
                    accessLevel,
                    documentId,
                    generation,
                    from,
                    to
            ));
        }

        return result.stream()
                .sorted(java.util.Comparator.comparingInt(
                        SearchProjection::chunkIndex
                ))
                .toList();
    }

    @Override
    public List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        requireAccessLevels(accessLevels);
        if (query == null || query.isBlank() || limit <= 0) {
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
                SELECT p.*, l.access_level AS resolved_access_level,
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
                SELECT p.*, l.access_level AS resolved_access_level,
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
                SELECT p.*, l.access_level AS resolved_access_level,
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
                SELECT p.*, l.access_level AS resolved_access_level,
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

    private GenerationIdentity identityFor(
            String documentId,
            long generation
    ) {
        return jdbcTemplate.query(
                """
                SELECT access_level
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                """,
                (rs, rowNum) -> new GenerationIdentity(
                        documentId,
                        generation,
                        rs.getLong("access_level")
                ),
                documentId,
                generation
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "Generation identity does not exist: "
                                + documentId
                                + "/"
                                + generation
                )
        );
    }

    private void requireGenerationIdentity(
            GenerationIdentity identity,
            List<SearchProjection> projections
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (projections == null || projections.isEmpty()) {
            return;
        }
        boolean mismatch = projections.stream().anyMatch(
                projection -> !identity.documentId().equals(
                                projection.documentId()
                        )
                        || identity.generation()
                                != projection.generation()
                        || (
                            projection.accessLevel() > 0
                            && identity.accessLevel()
                                    != projection.accessLevel()
                        )
        );
        if (mismatch) {
            throw new IllegalArgumentException(
                    "All projections must belong to generation identity"
            );
        }
    }

    private List<Long> routedAccessLevels(
            Set<Long> accessLevels
    ) {
        requireAccessLevels(accessLevels);
        return accessLevels.stream()
                .peek(value -> {
                    if (value == null || value <= 0) {
                        throw new IllegalArgumentException(
                                "accessLevels must contain positive values"
                        );
                    }
                })
                .distinct()
                .sorted()
                .toList();
    }

    private void requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
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

    private record GenerationKey(
            String documentId,
            long generation
    ) {
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
                accessLevel(rs),
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

    private long accessLevel(ResultSet rs) {
        try {
            long value = rs.getLong("access_level");
            if (!rs.wasNull()) {
                return value;
            }
        } catch (SQLException ignored) {
            // Transitional old schema has no physical projection ACL.
        }

        try {
            long value = rs.getLong("resolved_access_level");
            return rs.wasNull() ? 0L : value;
        } catch (SQLException ignored) {
            return 0L;
        }
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
