package kz.alimbetov.akmai.knowledge.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.model.RetrievalLanguageCatalog;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PublishedVectorSearchRepository {

    private static final int HNSW_EF_SEARCH = 40;
    private static final int ANN_RETRY_MULTIPLIER = 4;
    private static final int ANN_MAX_CANDIDATES_PER_BRANCH = 4096;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingProfileService profileService;
    private final EmbeddingProfileStorageManager storageManager;
    private final TransactionTemplate transactionTemplate;

    public PublishedVectorSearchRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Qualifier("retrievalEmbeddingModel")
            EmbeddingModel embeddingModel,
            EmbeddingProfileService profileService,
            EmbeddingProfileStorageManager storageManager,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
        this.profileService = profileService;
        this.storageManager = storageManager;
        this.transactionTemplate = transactionTemplate;
    }

    public List<VectorSearchMatch> search(
            String query,
            List<String> documentIds,
            Set<Long> accessLevels,
            int topK,
            double similarityThreshold
    ) {
        return search(
                query,
                null,
                documentIds,
                accessLevels,
                topK,
                similarityThreshold
        );
    }

    public List<VectorSearchMatch> search(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int topK,
            double similarityThreshold
    ) {
        List<Long> routedAccessLevels = routedAccessLevels(accessLevels);
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        profileService.assertConfiguredProfileIsActive();
        EmbeddingProfile profile = profileService.activeProfile();
        float[] raw = embeddingModel.embed(query);
        validateEmbedding(raw, profile.dimensions());

        PGvector vector = new PGvector(raw);
        double maxDistance = 1.0 - similarityThreshold;
        String routedLanguage = routedLanguage(language);

        List<VectorSearchMatch> local = routedSearch(
                profile,
                vector,
                documentIds,
                routedAccessLevels,
                routedLanguage,
                topK,
                maxDistance
        );

        if (routedLanguage == null || local.size() >= topK) {
            return local;
        }

        List<VectorSearchMatch> fallback = routedSearch(
                profile,
                vector,
                documentIds,
                routedAccessLevels,
                null,
                topK,
                maxDistance
        );

        java.util.LinkedHashMap<String, VectorSearchMatch> merged =
                new java.util.LinkedHashMap<>();
        java.util.stream.Stream.concat(
                        local.stream(),
                        fallback.stream()
                )
                .sorted(java.util.Comparator
                        .comparingDouble(VectorSearchMatch::score)
                        .reversed()
                        .thenComparing(VectorSearchMatch::vectorId))
                .forEach(match ->
                        merged.putIfAbsent(match.vectorId(), match)
                );

        return merged.values().stream()
                .limit(topK)
                .toList();
    }

    private List<VectorSearchMatch> routedSearch(
            EmbeddingProfile profile,
            PGvector vector,
            List<String> documentIds,
            List<Long> accessLevels,
            String language,
            int topK,
            double maxDistance
    ) {
        if (documentIds == null || documentIds.isEmpty()) {
            return globalSearch(
                    profile,
                    vector,
                    accessLevels,
                    language,
                    topK,
                    maxDistance
            );
        }
        return execute(
                documentExact(
                        profile,
                        vector,
                        documentIds,
                        accessLevels,
                        language,
                        topK,
                        maxDistance
                ),
                profile
        );
    }

    private List<VectorSearchMatch> globalSearch(
            EmbeddingProfile profile,
            PGvector vector,
            List<Long> accessLevels,
            String language,
            int topK,
            double maxDistance
    ) {
        int candidateLimit = topK;
        int maxCandidateLimit = Math.max(
                topK,
                ANN_MAX_CANDIDATES_PER_BRANCH
        );

        while (true) {
            AnnQueryResult result = executeAnn(
                    globalAnn(
                            profile,
                            vector,
                            accessLevels,
                            language,
                            topK,
                            candidateLimit,
                            maxDistance
                    ),
                    profile
            );
            if (!result.needsRetry()) {
                return result.matches();
            }
            if (candidateLimit >= maxCandidateLimit) {
                return execute(
                        fencedGlobalAnn(
                                profile,
                                vector,
                                accessLevels,
                                language,
                                topK,
                                maxDistance
                        ),
                        profile
                );
            }

            long expanded = Math.max(
                    (long) candidateLimit + 1L,
                    (long) candidateLimit * ANN_RETRY_MULTIPLIER
            );
            candidateLimit = (int) Math.min(
                    maxCandidateLimit,
                    expanded
            );
        }
    }

    private List<VectorSearchMatch> execute(
            SearchStatement statement,
            EmbeddingProfile profile
    ) {
        List<VectorSearchMatch> result =
                transactionTemplate.execute(status -> {
                    configureAnn(profile);
                    return jdbcTemplate.query(
                            statement.sql(),
                            ps -> bind(ps, statement.parameters()),
                            (rs, rowNum) -> new VectorSearchMatch(
                                    rs.getString("vector_id"),
                                    rs.getLong("access_level"),
                                    rs.getString("document_id"),
                                    rs.getLong("generation"),
                                    rs.getString("chunk_id"),
                                    rs.getString("content"),
                                    readMetadata(
                                            rs.getString("metadata_json")
                                    ),
                                    rs.getDouble("score")
                            )
                    );
                });

        return result == null ? List.of() : result;
    }

    private AnnQueryResult executeAnn(
            SearchStatement statement,
            EmbeddingProfile profile
    ) {
        AnnQueryResult result = transactionTemplate.execute(status -> {
            configureAnn(profile);
            List<VectorSearchMatch> matches = new ArrayList<>();
            boolean[] needsRetry = {false};

            jdbcTemplate.query(
                    statement.sql(),
                    ps -> bind(ps, statement.parameters()),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        needsRetry[0] = rs.getBoolean("needs_retry");
                        if (rs.getBoolean("control_row")) {
                            return;
                        }
                        matches.add(new VectorSearchMatch(
                                rs.getString("vector_id"),
                                rs.getLong("access_level"),
                                rs.getString("document_id"),
                                rs.getLong("generation"),
                                rs.getString("chunk_id"),
                                rs.getString("content"),
                                readMetadata(
                                        rs.getString("metadata_json")
                                ),
                                rs.getDouble("score")
                        ));
                    }
            );

            return new AnnQueryResult(
                    List.copyOf(matches),
                    needsRetry[0]
            );
        });

        return result == null
                ? new AnnQueryResult(List.of(), false)
                : result;
    }

    private void configureAnn(EmbeddingProfile profile) {
        if ("HNSW".equals(profile.indexType())) {
            jdbcTemplate.execute(
                    "SET LOCAL hnsw.iterative_scan = strict_order"
            );
            jdbcTemplate.execute(
                    "SET LOCAL hnsw.ef_search = "
                            + HNSW_EF_SEARCH
            );
        }
    }

    private SearchStatement globalAnn(
            EmbeddingProfile profile,
            PGvector vector,
            List<Long> accessLevels,
            String language,
            int topK,
            int candidateLimit,
            double maxDistance
    ) {
        String table = storageManager.qualified(profile);
        List<String> languages = language == null
                ? RetrievalLanguageCatalog.codes()
                : List.of(language);

        int branches = accessLevels.size() * languages.size();
        StringBuilder sql = new StringBuilder(
                "WITH branch_ids(branch_id) AS (VALUES "
        );
        for (int branch = 0; branch < branches; branch++) {
            if (branch > 0) {
                sql.append(", ");
            }
            sql.append("(").append(branch).append(")");
        }
        sql.append("), candidates AS MATERIALIZED (\n");

        List<Object> parameters = new ArrayList<>();
        int branch = 0;
        boolean firstBranch = true;

        for (long accessLevel : accessLevels) {
            for (String routedLanguage : languages) {
                if (!firstBranch) {
                    sql.append("\nUNION ALL\n");
                }
                firstBranch = false;

                sql.append("""
                        (
                            SELECT
                                %d AS branch_id,
                                v.id,
                                v.access_level,
                                v.document_id,
                                v.generation,
                                v.chunk_id,
                                v.content,
                                v.metadata,
                                v.embedding <=> ? AS distance
                            FROM %s v
                            WHERE v.access_level = ?
                              AND v.language = ?
                            ORDER BY v.embedding <=> ?
                            LIMIT ?
                        )
                        """.formatted(branch, table));

                parameters.add(vector);
                parameters.add(accessLevel);
                parameters.add(routedLanguage);
                parameters.add(vector);
                parameters.add(candidateLimit);
                branch++;
            }
        }

        sql.append("""
                ),
                visible AS MATERIALIZED (
                    SELECT candidate.*
                    FROM candidates candidate
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = candidate.document_id
                     AND l.published_generation = candidate.generation
                     AND l.access_level = candidate.access_level
                    WHERE l.retention_status = 'ACTIVE'
                ),
                raw_counts AS (
                    SELECT branch_id, count(*) AS row_count
                    FROM candidates
                    GROUP BY branch_id
                ),
                visible_counts AS (
                    SELECT branch_id, count(*) AS row_count
                    FROM visible
                    GROUP BY branch_id
                ),
                health AS (
                    SELECT COALESCE(
                        bool_or(
                            COALESCE(raw.row_count, 0) = ?
                            AND COALESCE(visible_count.row_count, 0) < ?
                        ),
                        false
                    ) AS needs_retry
                    FROM branch_ids branch
                    LEFT JOIN raw_counts raw
                      ON raw.branch_id = branch.branch_id
                    LEFT JOIN visible_counts visible_count
                      ON visible_count.branch_id = branch.branch_id
                ),
                top_visible AS (
                    SELECT *
                    FROM visible
                    WHERE distance <= ?
                    ORDER BY distance
                    LIMIT ?
                )
                SELECT
                    false AS control_row,
                    result.id::text AS vector_id,
                    result.access_level,
                    result.document_id,
                    result.generation,
                    result.chunk_id,
                    result.content,
                    result.metadata::text AS metadata_json,
                    1.0 - result.distance AS score,
                    health.needs_retry
                FROM top_visible result
                CROSS JOIN health

                UNION ALL

                SELECT
                    true AS control_row,
                    NULL::text AS vector_id,
                    NULL::bigint AS access_level,
                    NULL::varchar AS document_id,
                    NULL::bigint AS generation,
                    NULL::varchar AS chunk_id,
                    NULL::text AS content,
                    NULL::text AS metadata_json,
                    NULL::double precision AS score,
                    health.needs_retry
                FROM health
                ORDER BY control_row, score DESC NULLS LAST
                """);

        parameters.add(candidateLimit);
        parameters.add(topK);
        parameters.add(maxDistance);
        parameters.add(topK);

        return new SearchStatement(
                sql.toString(),
                List.copyOf(parameters)
        );
    }

    private SearchStatement fencedGlobalAnn(
            EmbeddingProfile profile,
            PGvector vector,
            List<Long> accessLevels,
            String language,
            int topK,
            double maxDistance
    ) {
        String table = storageManager.qualified(profile);
        List<String> languages = language == null
                ? RetrievalLanguageCatalog.codes()
                : List.of(language);

        StringBuilder sql = new StringBuilder("""
                SELECT candidate.id::text AS vector_id,
                       candidate.access_level,
                       candidate.document_id,
                       candidate.generation,
                       candidate.chunk_id,
                       candidate.content,
                       candidate.metadata::text AS metadata_json,
                       1.0 - candidate.distance AS score
                FROM (
                """);
        List<Object> parameters = new ArrayList<>();
        boolean firstBranch = true;

        for (long accessLevel : accessLevels) {
            for (String routedLanguage : languages) {
                if (!firstBranch) {
                    sql.append("\nUNION ALL\n");
                }
                firstBranch = false;

                sql.append("""
                        (
                            SELECT
                                v.id,
                                v.access_level,
                                v.document_id,
                                v.generation,
                                v.chunk_id,
                                v.content,
                                v.metadata,
                                v.embedding <=> ? AS distance
                            FROM %s v
                            JOIN knowledge_document_lifecycle l
                              ON l.document_id = v.document_id
                             AND l.published_generation = v.generation
                             AND l.access_level = v.access_level
                            WHERE v.access_level = ?
                              AND v.language = ?
                              AND l.retention_status = 'ACTIVE'
                            ORDER BY v.embedding <=> ?
                            LIMIT ?
                        )
                        """.formatted(table));

                parameters.add(vector);
                parameters.add(accessLevel);
                parameters.add(routedLanguage);
                parameters.add(vector);
                parameters.add(topK);
            }
        }

        sql.append("""
                ) candidate
                WHERE candidate.distance <= ?
                ORDER BY candidate.distance
                LIMIT ?
                """);
        parameters.add(maxDistance);
        parameters.add(topK);

        return new SearchStatement(
                sql.toString(),
                List.copyOf(parameters)
        );
    }

    private SearchStatement documentExact(
            EmbeddingProfile profile,
            PGvector vector,
            List<String> documentIds,
            List<Long> accessLevels,
            String language,
            int topK,
            double maxDistance
    ) {
        String table = storageManager.qualified(profile);
        StringBuilder sql = new StringBuilder("""
                WITH candidates AS MATERIALIZED (
                """);
        List<Object> parameters = new ArrayList<>();

        for (int index = 0; index < accessLevels.size(); index++) {
            if (index > 0) {
                sql.append("\nUNION ALL\n");
            }

            sql.append("""
                    SELECT
                        v.id,
                        v.access_level,
                        v.document_id,
                        v.generation,
                        v.chunk_id,
                        v.content,
                        v.metadata,
                        v.embedding
                    FROM knowledge_document_lifecycle l
                    JOIN %s v
                      ON v.access_level = ?
                     AND v.document_id = l.document_id
                     AND v.generation = l.published_generation
                    WHERE l.access_level = ?
                      AND l.retention_status = 'ACTIVE'
                      AND l.document_id = ANY (?)
                    """.formatted(table));
            if (language != null) {
                sql.append(" AND v.language = ?\n");
            }

            long accessLevel = accessLevels.get(index);
            parameters.add(accessLevel);
            parameters.add(accessLevel);
            parameters.add(documentIds);
            if (language != null) {
                parameters.add(language);
            }
        }

        sql.append("""
                )
                SELECT candidate.id::text AS vector_id,
                       candidate.access_level,
                       candidate.document_id,
                       candidate.generation,
                       candidate.chunk_id,
                       candidate.content,
                       candidate.metadata::text AS metadata_json,
                       1.0 - (candidate.embedding <=> ?) AS score
                FROM candidates candidate
                WHERE (candidate.embedding <=> ?) <= ?
                ORDER BY candidate.embedding <=> ?
                LIMIT ?
                """);
        parameters.add(vector);
        parameters.add(vector);
        parameters.add(maxDistance);
        parameters.add(vector);
        parameters.add(topK);

        return new SearchStatement(
                sql.toString(),
                List.copyOf(parameters)
        );
    }

    private String routedLanguage(String language) {
        String normalized =
                RetrievalLanguageCatalog.normalizeOrUnknown(language);
        return RetrievalLanguageCatalog.UNKNOWN.equals(normalized)
                ? null
                : normalized;
    }

    private List<Long> routedAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }

        List<Long> result = accessLevels.stream()
                .peek(value -> {
                    if (value == null || value <= 0) {
                        throw new IllegalArgumentException(
                                "accessLevels must contain positive values"
                        );
                    }
                })
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        return result;
    }

    private void bind(
            PreparedStatement ps,
            List<Object> parameters
    ) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            int parameter = index + 1;

            if (value instanceof List<?> values) {
                ps.setArray(
                        parameter,
                        ps.getConnection().createArrayOf(
                                "varchar",
                                values.toArray()
                        )
                );
            } else {
                ps.setObject(parameter, value);
            }
        }
    }

    private void validateEmbedding(
            float[] embedding,
            int dimensions
    ) {
        if (embedding == null || embedding.length != dimensions) {
            throw new IllegalStateException(
                    "Embedding model returned incompatible dimensions"
            );
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(
                        "Embedding model returned non-finite component"
                );
            }
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot parse vector metadata",
                    exception
            );
        }
    }

    private record SearchStatement(
            String sql,
            List<Object> parameters
    ) {
    }

    private record AnnQueryResult(
            List<VectorSearchMatch> matches,
            boolean needsRetry
    ) {
    }
}
