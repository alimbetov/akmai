package kz.alimbetov.akmai.knowledge.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

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
            int topK,
            double similarityThreshold
    ) {
        return search(
                query,
                documentIds,
                Set.of(),
                topK,
                similarityThreshold
        );
    }

    public List<VectorSearchMatch> search(
            String query,
            List<String> documentIds,
            Set<Long> accessLevels,
            int topK,
            double similarityThreshold
    ) {
        if (query == null
                || query.isBlank()
                || topK <= 0
                || accessLevels == null
                || accessLevels.isEmpty()) {
            return List.of();
        }
        profileService.assertConfiguredProfileIsActive();
        EmbeddingProfile profile = profileService.activeProfile();
        float[] raw = embeddingModel.embed(query);
        validateEmbedding(raw, profile.dimensions());
        PGvector vector = new PGvector(raw);
        double maxDistance = 1.0 - similarityThreshold;

        String scope = documentIds == null || documentIds.isEmpty()
                ? ""
                : " AND (v.metadata->>'akmaiDocumentId') = ANY (?) ";

        String sql = """
                SELECT v.id::text AS vector_id,
                       v.content,
                       v.metadata::text AS metadata_json,
                       v.metadata->>'akmaiDocumentId' AS document_id,
                       (v.metadata->>'akmaiGeneration')::bigint AS generation,
                       v.metadata->>'akmaiChunkId' AS chunk_id,
                       1.0 - (v.embedding <=> ?) AS score
                FROM %s v
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = v.metadata->>'akmaiDocumentId'
                 AND l.published_generation =
                        (v.metadata->>'akmaiGeneration')::bigint
                WHERE l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                  AND v.metadata->>'akmaiEmbeddingProfileId' = ?
                  AND (v.embedding <=> ?) <= ?
                """.formatted(storageManager.qualified(profile))
                + scope
                + """
                ORDER BY v.embedding <=> ?
                LIMIT ?
                """;

        List<VectorSearchMatch> result = transactionTemplate.execute(status -> {
            if ("HNSW".equals(profile.indexType())) {
                jdbcTemplate.execute(
                        "SET LOCAL hnsw.iterative_scan = strict_order"
                );
            }
            return jdbcTemplate.query(
                    sql,
                    ps -> bindSearch(
                            ps,
                            vector,
                            profile.profileId(),
                            maxDistance,
                            documentIds,
                            accessLevels,
                            topK
                    ),
                    (rs, rowNum) -> new VectorSearchMatch(
                            rs.getString("vector_id"),
                            rs.getString("document_id"),
                            rs.getLong("generation"),
                            rs.getString("chunk_id"),
                            rs.getString("content"),
                            readMetadata(rs.getString("metadata_json")),
                            rs.getDouble("score")
                    )
            );
        });
        return result == null ? List.of() : result;
    }

    private void bindSearch(
            PreparedStatement ps,
            PGvector vector,
            String profileId,
            double maxDistance,
            List<String> documentIds,
            Set<Long> accessLevels,
            int topK
    ) throws SQLException {
        int index = 1;
        ps.setObject(index++, vector);
        ps.setArray(
                index++,
                ps.getConnection().createArrayOf(
                        "bigint",
                        accessLevels.toArray()
                )
        );
        ps.setString(index++, profileId);
        ps.setObject(index++, vector);
        ps.setDouble(index++, maxDistance);
        if (documentIds != null && !documentIds.isEmpty()) {
            ps.setArray(
                    index++,
                    ps.getConnection().createArrayOf(
                            "varchar",
                            documentIds.toArray()
                    )
            );
        }
        ps.setObject(index++, vector);
        ps.setInt(index, topK);
    }

    private void validateEmbedding(float[] embedding, int dimensions) {
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
            throw new IllegalStateException("Cannot parse vector metadata", exception);
        }
    }
}
