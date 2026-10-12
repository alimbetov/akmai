package kz.alimbetov.akmai.knowledge.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.lifecycle.PublishedLifecycleSql;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.RetrievalLanguageCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Internal read-side specialization that keeps bounded candidate selection behind the
 * canonical READY + ACTIVE + TTL fence. It is owned by
 * {@link TimedPublishedSearchProjectionReader} and is deliberately not a Spring bean,
 * so published retrieval cannot bypass the timed read boundary. Write/direct-read
 * behavior remains in {@link PostgresSearchProjectionRepository}.
 */
public class CanonicalPostgresSearchProjectionRepository
        extends PostgresSearchProjectionRepository {

    static final int MAX_KEYS_PER_QUERY = 256;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CanonicalPostgresSearchProjectionRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        super(jdbcTemplate, objectMapper);
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SearchProjection> findPublishedByKeys(
            List<ProjectionKey> keys,
            Set<Long> accessLevels
    ) {
        requireAccessLevels(accessLevels);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<ProjectionKey, Integer> ordered = new LinkedHashMap<>();
        for (ProjectionKey key : keys) {
            if (key != null && accessLevels.contains(key.accessLevel())) {
                ordered.putIfAbsent(key, ordered.size());
            }
        }
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<ProjectionKey, Integer>> requested =
                List.copyOf(ordered.entrySet());
        List<OrderedProjection> found = new ArrayList<>();
        for (int from = 0; from < requested.size(); from += MAX_KEYS_PER_QUERY) {
            int to = Math.min(requested.size(), from + MAX_KEYS_PER_QUERY);
            found.addAll(findPublishedBatch(requested.subList(from, to)));
        }
        return found.stream()
                .sorted(java.util.Comparator.comparingInt(OrderedProjection::ordinal))
                .map(OrderedProjection::projection)
                .toList();
    }

    private List<OrderedProjection> findPublishedBatch(
            List<Map.Entry<ProjectionKey, Integer>> requested
    ) {
        String values = String.join(
                ", ",
                java.util.Collections.nCopies(requested.size(), "(?, ?, ?, ?, ?)")
        );
        String sql = """
                WITH requested(
                    ordinal,
                    access_level,
                    document_id,
                    generation,
                    chunk_id
                ) AS (VALUES
                """ + values + """
                )
                SELECT r.ordinal, p.*
                FROM requested r
                JOIN knowledge_search_projection p
                  ON p.access_level = r.access_level
                 AND p.document_id = r.document_id
                 AND p.generation = r.generation
                 AND p.chunk_id = r.chunk_id
                JOIN knowledge_document_lifecycle l
                  ON l.access_level = p.access_level
                 AND l.document_id = p.document_id
                 AND l.published_generation = p.generation
                WHERE """ + PublishedLifecycleSql.eligible("l") + """
                ORDER BY r.ordinal
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    int index = 1;
                    for (Map.Entry<ProjectionKey, Integer> entry : requested) {
                        ProjectionKey key = entry.getKey();
                        ps.setInt(index++, entry.getValue());
                        ps.setLong(index++, key.accessLevel());
                        ps.setString(index++, key.documentId());
                        ps.setLong(index++, key.generation());
                        ps.setString(index++, key.chunkId());
                    }
                },
                (rs, rowNum) -> new OrderedProjection(
                        rs.getInt("ordinal"),
                        mapProjection(rs, rowNum)
                )
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
        requireAccessLevels(accessLevels);
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        LexicalSearchLanguage searchLanguage = LexicalSearchLanguage.from(language);
        String routedLanguage = RetrievalLanguageCatalog.normalizeOrUnknown(language);
        return switch (searchLanguage) {
            case RU -> searchFtsWithLanguageFallback(
                    query, "ru", documentIds, accessLevels, limit,
                    "search_vector_ru", "russian");
            case EN -> searchFtsWithLanguageFallback(
                    query, "en", documentIds, accessLevels, limit,
                    "search_vector_en", "english");
            case KK -> searchTrigram(query, "kk", documentIds, accessLevels, limit);
            case ZH -> searchTrigram(query, "zh", documentIds, accessLevels, limit);
            case DE, FR, ES, PT, IT, TR, EL -> searchSimpleWithLanguageFallback(
                    query, searchLanguage.code(), documentIds, accessLevels, limit);
            case UNKNOWN -> searchSimple(
                    query,
                    RetrievalLanguageCatalog.UNKNOWN.equals(routedLanguage)
                            ? null : routedLanguage,
                    documentIds,
                    accessLevels,
                    limit);
        };
    }

    @Override
    public List<SearchProjection> searchSemanticConcepts(
            List<String> conceptIds,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        requireAccessLevels(accessLevels);
        if (limit <= 0 || conceptIds == null || conceptIds.isEmpty()) {
            return List.of();
        }
        List<String> requestedConcepts = conceptIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(16)
                .toList();
        if (requestedConcepts.isEmpty()) {
            return List.of();
        }
        List<String> scopedDocuments = documentIds == null
                ? List.of()
                : documentIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
        Set<String> requestedSet = Set.copyOf(requestedConcepts);
        List<SearchProjection> result = new ArrayList<>();

        for (long accessLevel : routedAccessLevels(accessLevels)) {
            boolean documentScoped = !scopedDocuments.isEmpty();
            String documentPredicate = documentScoped
                    ? "  AND p.document_id = ANY (?::varchar[])\n" : "";
            String sql = """
                    SELECT p.*
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND """ + PublishedLifecycleSql.eligible("l") + """
                      AND (p.metadata_json -> 'semanticConcepts') ??| ?::text[]
                    """ + documentPredicate + """
                    ORDER BY (
                        SELECT count(*)
                        FROM jsonb_array_elements_text(
                            CASE
                                WHEN jsonb_typeof(p.metadata_json -> 'semanticConcepts') = 'array'
                                THEN p.metadata_json -> 'semanticConcepts'
                                ELSE '[]'::jsonb
                            END
                        ) AS matched(concept_id)
                        WHERE matched.concept_id = ANY (?::text[])
                    ) DESC,
                    p.document_id,
                    p.chunk_index
                    LIMIT ?
                    """;
            result.addAll(jdbcTemplate.query(
                    sql,
                    ps -> {
                        int index = 1;
                        ps.setLong(index++, accessLevel);
                        bindArray(ps, index++, requestedConcepts);
                        if (documentScoped) {
                            bindArray(ps, index++, scopedDocuments);
                        }
                        bindArray(ps, index++, requestedConcepts);
                        ps.setInt(index, limit);
                    },
                    this::mapProjection
            ));
        }
        return result.stream()
                .sorted(java.util.Comparator
                        .<SearchProjection>comparingInt(p -> semanticConceptOverlap(p, requestedSet))
                        .reversed()
                        .thenComparing(SearchProjection::documentId)
                        .thenComparingInt(SearchProjection::chunkIndex))
                .limit(limit)
                .toList();
    }

    private List<SearchProjection> searchSimpleWithLanguageFallback(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        List<SearchProjection> simple = searchSimple(
                query, language, documentIds, accessLevels, limit);
        return simple.isEmpty()
                ? searchLanguageTrigramFallback(
                        query, language, documentIds, accessLevels, limit)
                : simple;
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
                query, language, documentIds, accessLevels, limit,
                vectorColumn, configuration);
        return fts.isEmpty()
                ? searchLanguageTrigramFallback(
                        query, language, documentIds, accessLevels, limit)
                : fts;
    }

    private List<SearchProjection> searchLanguageTrigramFallback(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        List<String> terms = java.util.Arrays.stream(
                        query.toLowerCase(java.util.Locale.ROOT).split("\\s+"))
                .map(term -> term.replaceAll(
                        "(?U)^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", ""))
                .filter(term -> term.length() >= 3)
                .distinct()
                .limit(8)
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Long> routed = routedAccessLevels(accessLevels);
        String score = terms.stream()
                .map(ignored -> "greatest(word_similarity(lower(?), lower(p.text_content)), "
                        + "word_similarity(lower(?), lower(coalesce(p.section_path, ''))))")
                .collect(java.util.stream.Collectors.joining(" + "));
        String predicate = terms.stream()
                .map(ignored -> "greatest(word_similarity(lower(?), lower(p.text_content)), "
                        + "word_similarity(lower(?), lower(coalesce(p.section_path, '')))) >= 0.45")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String branch = """
                (
                    SELECT p.*, (%s) / %d AS lexical_rank
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND %s
                      AND p.language = ?
                      AND (%s)
                """.formatted(
                        score,
                        terms.size(),
                        PublishedLifecycleSql.eligible("l"),
                        predicate)
                + documentFilter(documentIds) + """
                    ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                    LIMIT ?
                )
                """;
        List<Object> parameters = new ArrayList<>();
        for (long accessLevel : routed) {
            for (String term : terms) {
                parameters.add(term);
                parameters.add(term);
            }
            parameters.add(accessLevel);
            parameters.add(language);
            for (String term : terms) {
                parameters.add(term);
                parameters.add(term);
            }
            if (documentIds != null && !documentIds.isEmpty()) {
                parameters.add(documentIds);
            }
            parameters.add(limit);
        }
        parameters.add(limit);
        return queryLexical(lexicalUnionSql(branch, routed.size()), parameters);
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
        List<Long> routed = routedAccessLevels(accessLevels);
        String branch = """
                (
                    SELECT p.*,
                           ts_rank(p.%s, websearch_to_tsquery('%s', ?)) AS lexical_rank
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND %s
                      AND p.language = ?
                      AND p.%s @@ websearch_to_tsquery('%s', ?)
                """.formatted(
                        vectorColumn,
                        configuration,
                        PublishedLifecycleSql.eligible("l"),
                        vectorColumn,
                        configuration)
                + documentFilter(documentIds) + """
                    ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                    LIMIT ?
                )
                """;
        List<Object> parameters = new ArrayList<>();
        for (long accessLevel : routed) {
            parameters.add(query);
            parameters.add(accessLevel);
            parameters.add(language);
            parameters.add(query);
            if (documentIds != null && !documentIds.isEmpty()) {
                parameters.add(documentIds);
            }
            parameters.add(limit);
        }
        parameters.add(limit);
        return queryLexical(lexicalUnionSql(branch, routed.size()), parameters);
    }

    private List<SearchProjection> searchTrigram(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        List<Long> routed = routedAccessLevels(accessLevels);
        String escaped = escapeLikeLiteral(query);
        String branch = """
                (
                    SELECT p.*,
                           greatest(
                               similarity(lower(p.text_content), lower(?)),
                               similarity(lower(coalesce(p.section_path, '')), lower(?))
                           ) AS lexical_rank
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND %s
                      AND p.language = ?
                      AND (
                          lower(p.text_content) %% lower(?)
                          OR lower(coalesce(p.section_path, '')) %% lower(?)
                          OR lower(p.text_content) LIKE ('%%' || lower(?) || '%%') ESCAPE '\\'
                      )
                """.formatted(PublishedLifecycleSql.eligible("l"))
                + documentFilter(documentIds) + """
                    ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                    LIMIT ?
                )
                """;
        List<Object> parameters = new ArrayList<>();
        for (long accessLevel : routed) {
            parameters.add(query);
            parameters.add(query);
            parameters.add(accessLevel);
            parameters.add(language);
            parameters.add(query);
            parameters.add(query);
            parameters.add(escaped);
            if (documentIds != null && !documentIds.isEmpty()) {
                parameters.add(documentIds);
            }
            parameters.add(limit);
        }
        parameters.add(limit);
        return queryLexical(lexicalUnionSql(branch, routed.size()), parameters);
    }

    private List<SearchProjection> searchSimple(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        List<Long> routed = routedAccessLevels(accessLevels);
        String languageFilter = language == null ? "" : "      AND p.language = ?\n";
        String branch = """
                (
                    SELECT p.*,
                           ts_rank(p.search_vector, websearch_to_tsquery('simple', ?)) AS lexical_rank
                    FROM knowledge_search_projection p
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = p.document_id
                     AND l.published_generation = p.generation
                     AND l.access_level = p.access_level
                    WHERE p.access_level = ?
                      AND %s
                """.formatted(PublishedLifecycleSql.eligible("l"))
                + languageFilter + """
                      AND p.search_vector @@ websearch_to_tsquery('simple', ?)
                """ + documentFilter(documentIds) + """
                    ORDER BY lexical_rank DESC, p.document_id, p.chunk_id
                    LIMIT ?
                )
                """;
        List<Object> parameters = new ArrayList<>();
        for (long accessLevel : routed) {
            parameters.add(query);
            parameters.add(accessLevel);
            if (language != null) {
                parameters.add(language);
            }
            parameters.add(query);
            if (documentIds != null && !documentIds.isEmpty()) {
                parameters.add(documentIds);
            }
            parameters.add(limit);
        }
        parameters.add(limit);
        return queryLexical(lexicalUnionSql(branch, routed.size()), parameters);
    }

    private String lexicalUnionSql(String branch, int branchCount) {
        String branches = String.join(
                "\nUNION ALL\n",
                java.util.Collections.nCopies(branchCount, branch));
        return """
                SELECT *
                FROM (
                """ + branches + """
                ) lexical
                ORDER BY lexical_rank DESC, document_id, chunk_id
                LIMIT ?
                """;
    }

    private List<SearchProjection> queryLexical(
            String sql,
            List<Object> parameters
    ) {
        return jdbcTemplate.query(
                sql,
                ps -> {
                    for (int index = 0; index < parameters.size(); index++) {
                        Object value = parameters.get(index);
                        if (value instanceof List<?> list) {
                            ps.setArray(
                                    index + 1,
                                    ps.getConnection().createArrayOf(
                                            "varchar", list.toArray()));
                        } else {
                            ps.setObject(index + 1, value);
                        }
                    }
                },
                this::mapProjection);
    }

    private String documentFilter(List<String> documentIds) {
        return documentIds == null || documentIds.isEmpty()
                ? "" : " AND p.document_id = ANY (?) ";
    }

    private List<Long> routedAccessLevels(Set<Long> accessLevels) {
        requireAccessLevels(accessLevels);
        return accessLevels.stream()
                .peek(value -> {
                    if (value == null || value <= 0) {
                        throw new IllegalArgumentException(
                                "accessLevels must contain positive values");
                    }
                })
                .distinct()
                .sorted()
                .toList();
    }

    private void requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException("accessLevels must not be empty");
        }
    }

    private int semanticConceptOverlap(
            SearchProjection projection,
            Set<String> requestedConcepts
    ) {
        Object raw = projection.metadata().get("semanticConcepts");
        if (!(raw instanceof Iterable<?> values)) {
            return 0;
        }
        int overlap = 0;
        for (Object value : values) {
            if (value instanceof String conceptId
                    && requestedConcepts.contains(conceptId)) {
                overlap++;
            }
        }
        return overlap;
    }

    private void bindArray(
            PreparedStatement ps,
            int index,
            List<String> values
    ) throws SQLException {
        ps.setArray(
                index,
                ps.getConnection().createArrayOf("varchar", values.toArray()));
    }

    private String escapeLikeLiteral(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private SearchProjection mapProjection(ResultSet rs, int rowNum)
            throws SQLException {
        return new SearchProjection(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getLong("generation"),
                rs.getLong("access_level"),
                rs.getString("parent_chunk_id"),
                rs.getInt("chunk_index"),
                rs.getString("text_content"),
                rs.getString("embedding_text"),
                rs.getString("language"),
                KnowledgeDomain.valueOf(rs.getString("domain")),
                rs.getString("section_path"),
                readJson(
                        rs.getString("identifiers_json"),
                        new TypeReference<List<DetectedIdentifier>>() {}),
                readJson(
                        rs.getString("references_json"),
                        new TypeReference<List<String>>() {}),
                readJson(
                        rs.getString("metadata_json"),
                        new TypeReference<Map<String, Object>>() {}),
                rs.getInt("projection_version"));
    }

    private <T> T readJson(String json, TypeReference<T> type)
            throws SQLException {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException(
                    "Cannot deserialize search projection JSON",
                    exception);
        }
    }

    private record OrderedProjection(int ordinal, SearchProjection projection) {
    }
}
