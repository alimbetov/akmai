package kz.alimbetov.akmai.knowledge.graph.dream;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Enumerates only currently published, READY, ACTIVE and unexpired vectors.
 * Fast-lane keyset uses lifecycle.updated_at, which publication updates after
 * vector insertion inside the same transaction.
 */
@Repository
public class DreamSourceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProfileService profileService;
    private final EmbeddingProfileStorageManager storageManager;

    public DreamSourceRepository(
            JdbcTemplate jdbcTemplate,
            EmbeddingProfileService profileService,
            EmbeddingProfileStorageManager storageManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileService = profileService;
        this.storageManager = storageManager;
    }

    public List<DreamSource> findFastAfter(
            DreamCheckpointRepository.Watermark after,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        EmbeddingProfile profile = activeProfile();
        String table = storageManager.qualified(profile);
        String cursor = after == null
                ? ""
                : """
                  AND ROW(
                      l.updated_at,
                      v.access_level,
                      v.document_id,
                      v.generation,
                      v.chunk_id
                  ) > ROW(?::timestamptz, ?, ?, ?, ?)
                  """;
        String sql = """
                SELECT v.access_level,
                       v.document_id,
                       v.generation,
                       v.chunk_id,
                       v.language,
                       v.embedding::text AS embedding_text,
                       l.updated_at AS source_updated_at
                FROM %s v
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = v.document_id
                 AND l.access_level = v.access_level
                 AND l.published_generation = v.generation
                WHERE l.lifecycle_status = 'READY'
                  AND l.retention_status = 'ACTIVE'
                  AND (
                      l.expires_at IS NULL
                      OR l.expires_at > clock_timestamp()
                  )
                  %s
                ORDER BY l.updated_at,
                         v.access_level,
                         v.document_id,
                         v.generation,
                         v.chunk_id
                LIMIT ?
                """.formatted(table, cursor);

        if (after == null) {
            return jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> map(rs, profile.profileId()),
                    limit
            );
        }
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> map(rs, profile.profileId()),
                java.sql.Timestamp.from(after.updatedAt()),
                after.accessLevel(),
                after.documentId(),
                after.generation(),
                after.chunkId(),
                limit
        );
    }

    public Optional<DreamSource> findEligibleSource(ChunkGraphNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        EmbeddingProfile profile = activeProfile();
        String table = storageManager.qualified(profile);
        return jdbcTemplate.query(
                """
                SELECT v.access_level,
                       v.document_id,
                       v.generation,
                       v.chunk_id,
                       v.language,
                       v.embedding::text AS embedding_text,
                       l.updated_at AS source_updated_at
                FROM %s v
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = v.document_id
                 AND l.access_level = v.access_level
                 AND l.published_generation = v.generation
                WHERE v.access_level = ?
                  AND v.document_id = ?
                  AND v.generation = ?
                  AND v.chunk_id = ?
                  AND l.lifecycle_status = 'READY'
                  AND l.retention_status = 'ACTIVE'
                  AND (
                      l.expires_at IS NULL
                      OR l.expires_at > clock_timestamp()
                  )
                """.formatted(table),
                (rs, rowNum) -> map(rs, profile.profileId()),
                node.accessLevel(),
                node.documentId(),
                node.generation(),
                node.chunkId()
        ).stream().findFirst();
    }

    private EmbeddingProfile activeProfile() {
        profileService.assertConfiguredProfileIsActive();
        return profileService.activeProfile();
    }

    private DreamSource map(
            java.sql.ResultSet rs,
            String profileId
    ) throws java.sql.SQLException {
        return new DreamSource(
                new ChunkGraphNode(
                        rs.getLong("access_level"),
                        rs.getString("document_id"),
                        rs.getLong("generation"),
                        rs.getString("chunk_id")
                ),
                rs.getString("language"),
                parseVector(rs.getString("embedding_text")),
                rs.getTimestamp("source_updated_at").toInstant(),
                profileId
        );
    }

    static float[] parseVector(String raw) {
        if (raw == null || raw.length() < 2
                || raw.charAt(0) != '['
                || raw.charAt(raw.length() - 1) != ']') {
            throw new IllegalArgumentException("invalid pgvector text");
        }
        String body = raw.substring(1, raw.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] result = new float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            result[index] = Float.parseFloat(parts[index]);
            if (!Float.isFinite(result[index])) {
                throw new IllegalArgumentException("non-finite pgvector component");
            }
        }
        return result;
    }

    public record DreamSource(
            ChunkGraphNode node,
            String language,
            float[] embedding,
            Instant updatedAt,
            String embeddingProfileId
    ) {
        public DreamSource {
            if (node == null || updatedAt == null) {
                throw new IllegalArgumentException("Dream source identity is required");
            }
            if (language == null || language.isBlank()) {
                throw new IllegalArgumentException("Dream source language is required");
            }
            if (embedding == null || embedding.length == 0) {
                throw new IllegalArgumentException("Dream source embedding is required");
            }
            if (embeddingProfileId == null || embeddingProfileId.isBlank()) {
                throw new IllegalArgumentException("embeddingProfileId is required");
            }
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }

        public DreamCheckpointRepository.Watermark watermark() {
            return new DreamCheckpointRepository.Watermark(
                    updatedAt,
                    node.accessLevel(),
                    node.documentId(),
                    node.generation(),
                    node.chunkId()
            );
        }
    }
}
