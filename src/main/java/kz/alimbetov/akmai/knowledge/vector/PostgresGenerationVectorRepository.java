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
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (!hasTypedRouting(profile)) {
            insertLegacy(profile, rows);
            return;
        }

        String table = storageManager.qualified(profile);
        String sql = """
                INSERT INTO %s (
                    access_level,
                    document_id,
                    generation,
                    chunk_id,
                    id,
                    content,
                    metadata,
                    embedding
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.formatted(table);

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(
                    PreparedStatement ps,
                    int index
            ) throws SQLException {
                VectorRow row = rows.get(index);
                validateEmbedding(row, profile);
                ps.setLong(1, identity.accessLevel());
                ps.setString(2, identity.documentId());
                ps.setLong(3, identity.generation());
                ps.setString(4, row.chunkId());
                ps.setObject(5, UUID.fromString(row.vectorId()));
                ps.setString(6, row.content());
                ps.setString(7, json(row.metadata()));
                ps.setObject(8, new PGvector(row.embedding()));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public void insertAll(
            EmbeddingProfile profile,
            List<VectorRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (hasTypedRouting(profile)) {
            insertAll(
                    profile,
                    identityFromRows(rows),
                    rows
            );
            return;
        }
        insertLegacy(profile, rows);
    }

    private void insertLegacy(
            EmbeddingProfile profile,
            List<VectorRow> rows
    ) {
        String table = storageManager.qualified(profile);
        String sql = """
                INSERT INTO %s (id, content, metadata, embedding)
                VALUES (?, ?, ?::jsonb, ?)
                """.formatted(table);

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(
                    PreparedStatement ps,
                    int index
            ) throws SQLException {
                VectorRow row = rows.get(index);
                validateEmbedding(row, profile);
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
        if (!hasTypedRouting(profile)) {
            return deleteIds(profile, ids);
        }
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String table = storageManager.qualified(profile);
        int deleted = 0;
        for (String id : ids) {
            deleted += jdbcTemplate.update(
                    "DELETE FROM " + table
                            + " WHERE access_level = ? AND id = ?",
                    identity.accessLevel(),
                    UUID.fromString(id)
            );
        }
        return deleted;
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

    public List<String> findIdsByGeneration(
            EmbeddingProfile profile,
            GenerationIdentity identity
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (!hasTypedRouting(profile)) {
            return findIdsByGenerationMetadata(
                    profile,
                    identity.documentId(),
                    identity.generation()
            );
        }
        String table = storageManager.qualified(profile);
        return jdbcTemplate.queryForList(
                """
                SELECT id::text
                FROM %s
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                ORDER BY id
                """.formatted(table),
                String.class,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
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

    public int countExisting(
            EmbeddingProfile profile,
            GenerationIdentity identity,
            List<String> ids
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (!hasTypedRouting(profile)) {
            return countExisting(profile, ids);
        }
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String table = storageManager.qualified(profile);
        return jdbcTemplate.query(
                """
                SELECT count(*)
                FROM %s
                WHERE access_level = ?
                  AND id::text = ANY (?)
                """.formatted(table),
                ps -> {
                    ps.setLong(1, identity.accessLevel());
                    ps.setArray(
                            2,
                            ps.getConnection().createArrayOf(
                                    "varchar",
                                    ids.toArray()
                            )
                    );
                },
                rs -> {
                    rs.next();
                    return rs.getInt(1);
                }
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

    private void validateEmbedding(
            VectorRow row,
            EmbeddingProfile profile
    ) throws SQLException {
        if (row.embedding().length != profile.dimensions()) {
            throw new SQLException("Embedding dimension mismatch");
        }
        for (float value : row.embedding()) {
            if (!Float.isFinite(value)) {
                throw new SQLException(
                        "Non-finite embedding component"
                );
            }
        }
    }

    private boolean hasTypedRouting(EmbeddingProfile profile) {
        Boolean result = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = ?
                      AND column_name = 'access_level'
                )
                """,
                Boolean.class,
                profile.vectorSchema(),
                profile.vectorTable()
        );
        return Boolean.TRUE.equals(result);
    }

    private GenerationIdentity identityFromRows(List<VectorRow> rows) {
        VectorRow first = rows.getFirst();
        String documentId = textMetadata(
                first.metadata(),
                "akmaiDocumentId"
        );
        long generation = longMetadata(
                first.metadata(),
                "akmaiGeneration"
        );
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

    private String textMetadata(
            Map<String, Object> metadata,
            String key
    ) {
        Object value = metadata.get(key);
        if (!(value instanceof String text)
                || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing vector metadata: " + key
            );
        }
        return text;
    }

    private long longMetadata(
            Map<String, Object> metadata,
            String key
    ) {
        Object value = metadata.get(key);
        if (!(value instanceof Number number)
                || number.longValue() <= 0) {
            throw new IllegalArgumentException(
                    "Missing vector metadata: " + key
            );
        }
        return number.longValue();
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
            String chunkId,
            String content,
            Map<String, Object> metadata,
            float[] embedding
    ) {
        public VectorRow {
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException(
                        "chunkId must not be blank"
                );
            }
            metadata = Map.copyOf(metadata);
            embedding = embedding.clone();
        }

        public VectorRow(
                String vectorId,
                String content,
                Map<String, Object> metadata,
                float[] embedding
        ) {
            this(
                    vectorId,
                    String.valueOf(metadata.get("akmaiChunkId")),
                    content,
                    metadata,
                    embedding
            );
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }
}
