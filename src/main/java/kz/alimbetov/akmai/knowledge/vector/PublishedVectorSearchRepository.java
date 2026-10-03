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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PublishedVectorSearchRepository {

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

        SearchStatement statement = documentIds == null
                || documentIds.isEmpty()
                ? globalAnn(
                        profile,
                        vector,
                        routedAccessLevels,
                        topK,
                        maxDistance
                )
                : documentExact(
                        profile,
                        vector,
                        documentIds,
                        routedAccessLevels,
                        topK,
                        maxDistance
                );

        List<VectorSearchMatch> result =
                transactionTemplate.execute(status -> {
                    if ("HNSW".equals(profile.indexType())) {
                        jdbcTemplate.execute(
                                "SET LOCAL hnsw.iterative_scan = strict_order"
                        );
                    }
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

    private SearchStatement globalAnn(
            EmbeddingProfile profile,
            PGvector vector,
            List<Long> accessLevels,
            int topK,
            double maxDistance
    ) {
        String table = storageManager.qualified(profile);
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

        for (int index = 0; index < accessLevels.size(); index++) {
            if (index > 0) {
                sql.append("\nUNION ALL\n");
            }

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
                        WHERE v.access_level = ?
                        ORDER BY v.embedding <=> ?
                        LIMIT ?
                    )
                    """.formatted(table));

            parameters.add(vector);
            parameters.add(accessLevels.get(index));
            parameters.add(vector);
            parameters.add(topK);
        }

        sql.append("""
                ) candidate
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = candidate.document_id
                 AND l.published_generation = candidate.generation
                 AND l.access_level = candidate.access_level
                WHERE l.retention_status = 'ACTIVE'
                  AND candidate.distance <= ?
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

            long accessLevel = accessLevels.get(index);
            parameters.add(accessLevel);
            parameters.add(accessLevel);
            parameters.add(documentIds);
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
}
