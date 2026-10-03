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
        String table = storageManager.qualified(profile);
        String sql = """
                INSERT INTO %s (
                    access_level,
                    language,
                    document_id,
                    generation,
                    chunk_id,
                    id,
                    content,
                    metadata,
                    embedding
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
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
                ps.setString(2, row.language());
                ps.setString(3, identity.documentId());
                ps.setLong(4, identity.generation());
                ps.setString(5, row.chunkId());
                ps.setObject(6, UUID.fromString(row.vectorId()));
                ps.setString(7, row.content());
                ps.setString(8, json(row.metadata()));
                ps.setObject(9, new PGvector(row.embedding()));
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
        rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        this::generationKey,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ))
                .forEach((key, values) ->
                        insertAll(
                                profile,
                                identityFor(
                                        key.documentId(),
                                        key.generation()
                                ),
                                values
                        )
                );
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

    public List<String> findIdsByGeneration(
            EmbeddingProfile profile,
            GenerationIdentity identity
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
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

    private GenerationKey generationKey(VectorRow row) {
        return new GenerationKey(
                textMetadata(
                        row.metadata(),
                        "akmaiDocumentId"
                ),
                longMetadata(
                        row.metadata(),
                        "akmaiGeneration"
                )
        );
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

    private record GenerationKey(
            String documentId,
            long generation
    ) {
    }

    public record VectorRow(
            String vectorId,
            String chunkId,
            String language,
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
            if (language == null
                    || !language.matches("[a-z]{2,8}")) {
                throw new IllegalArgumentException(
                        "language must be a normalized code"
                );
            }
            metadata = Map.copyOf(metadata);
            embedding = embedding.clone();
        }

        public VectorRow(
                String vectorId,
                String chunkId,
                String content,
                Map<String, Object> metadata,
                float[] embedding
        ) {
            this(
                    vectorId,
                    chunkId,
                    languageMetadata(metadata),
                    content,
                    metadata,
                    embedding
            );
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
                    languageMetadata(metadata),
                    content,
                    metadata,
                    embedding
            );
        }

        private static String languageMetadata(
                Map<String, Object> metadata
        ) {
            Object value = metadata == null
                    ? null
                    : metadata.get("language");
            if (!(value instanceof String language)
                    || language.isBlank()) {
                throw new IllegalArgumentException(
                        "Vector row requires language"
                );
            }
            return language.toLowerCase(
                    java.util.Locale.ROOT
            );
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }
}
