package kz.alimbetov.akmai.knowledge.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresGenerationVectorRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingProfileStorageManager storageManager;

    public PostgresGenerationVectorRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EmbeddingProfileStorageManager storageManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.storageManager = storageManager;
    }

    public void insertAll(
            EmbeddingProfile profile,
            GenerationIdentity identity,
            List<VectorRow> rows
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        insertAll(profile, rows);
    }

    public void insertAll(
            EmbeddingProfile profile,
            List<VectorRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String table = storageManager.qualified(profile);
        String sql = """
                INSERT INTO %s (id, content, metadata, embedding)
                VALUES (?, ?, ?::jsonb, ?)
                """.formatted(table);

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index)
                    throws SQLException {
                VectorRow row = rows.get(index);
                if (row.embedding().length != profile.dimensions()) {
                    throw new SQLException("Embedding dimension mismatch");
                }
                for (float value : row.embedding()) {
                    if (!Float.isFinite(value)) {
                        throw new SQLException("Non-finite embedding component");
                    }
                }
                ps.setObject(1, UUID.fromString(row.vectorId()));
                ps.setString(2, row.content());
                ps.setString(3, json(row.metadata()));
                ps.setObject(4, new PGvector(row.embedding()));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public int deleteIds(
            EmbeddingProfile profile,
            GenerationIdentity identity,
            List<String> ids
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        return deleteIds(profile, ids);
    }

    public int deleteIds(EmbeddingProfile profile, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String table = storageManager.qualified(profile);
        int deleted = 0;
        for (String id : ids) {
            deleted += jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE id = ?",
                    UUID.fromString(id)
            );
        }
        return deleted;
    }

    public List<String> findIdsByGenerationMetadata(
            EmbeddingProfile profile,
            String documentId,
            long generation
    ) {
        String table = storageManager.qualified(profile);
        return jdbcTemplate.query(
                """
                SELECT id::text
                FROM %s
                WHERE metadata->>'akmaiDocumentId' = ?
                  AND (metadata->>'akmaiGeneration')::bigint = ?
                  AND metadata->>'akmaiEmbeddingProfileId' = ?
                ORDER BY id
                """.formatted(table),
                (rs, rowNum) -> rs.getString(1),
                documentId,
                generation,
                profile.profileId()
        );
    }

    public int countExisting(EmbeddingProfile profile, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String table = storageManager.qualified(profile);
        return jdbcTemplate.query(
                "SELECT count(*) FROM " + table + " WHERE id::text = ANY (?)",
                ps -> ps.setArray(
                        1,
                        ps.getConnection().createArrayOf("varchar", ids.toArray())
                ),
                rs -> {
                    rs.next();
                    return rs.getInt(1);
                }
        );
    }

    private String json(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize vector metadata", exception);
        }
    }

    public record VectorRow(
            String vectorId,
            String content,
            Map<String, Object> metadata,
            float[] embedding
    ) {
        public VectorRow {
            metadata = Map.copyOf(metadata);
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }
}
