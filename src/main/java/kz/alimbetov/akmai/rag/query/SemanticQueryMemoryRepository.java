package kz.alimbetov.akmai.rag.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SemanticQueryMemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public SemanticQueryMemoryRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public List<StoredCluster> findClustersByProfile(
            String embeddingProfileId,
            int limit
    ) {
        if (embeddingProfileId == null || embeddingProfileId.isBlank() || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT cluster_id,
                       embedding_profile_id,
                       required_access_levels::text,
                       centroid::text,
                       observation_count,
                       refresh_revision,
                       updated_at
                FROM rag_query_memory_cluster
                WHERE embedding_profile_id = ?
                ORDER BY refresh_revision DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapCluster(rs),
                embeddingProfileId,
                limit
        );
    }

    public List<StoredCluster> findClustersUpdatedAfter(
            String embeddingProfileId,
            RefreshCursor cursor,
            int limit
    ) {
        if (embeddingProfileId == null
                || embeddingProfileId.isBlank()
                || cursor == null
                || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT cluster_id,
                       embedding_profile_id,
                       required_access_levels::text,
                       centroid::text,
                       observation_count,
                       refresh_revision,
                       updated_at
                FROM rag_query_memory_cluster
                WHERE embedding_profile_id = ?
                  AND refresh_revision > ?
                ORDER BY refresh_revision
                LIMIT ?
                """,
                (rs, rowNum) -> mapCluster(rs),
                embeddingProfileId,
                cursor.revision(),
                limit
        );
    }

    public int deleteExpired(
            String embeddingProfileId,
            Instant cutoff
    ) {
        if (embeddingProfileId == null
                || embeddingProfileId.isBlank()
                || cutoff == null) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                DELETE FROM rag_query_memory_cluster
                WHERE embedding_profile_id = ?
                  AND updated_at < ?
                """,
                embeddingProfileId,
                java.sql.Timestamp.from(cutoff)
        );
    }

    public List<StoredObservation> findObservations(
            UUID clusterId,
            int limit
    ) {
        if (clusterId == null || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT grounded_answer,
                       source_refs::text,
                       observed_at
                FROM rag_query_memory_observation
                WHERE cluster_id = ?
                ORDER BY observed_at DESC, observation_id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new StoredObservation(
                        rs.getString("grounded_answer"),
                        readStringList(rs.getString("source_refs")),
                        rs.getTimestamp("observed_at").toInstant()
                ),
                clusterId,
                limit
        );
    }

    public boolean persist(
            StoredCluster cluster,
            String queryFingerprint,
            String groundedAnswer,
            List<String> sourceRefs,
            Instant observedAt,
            int maxClusters,
            int maxObservationsPerCluster
    ) {
        if (cluster == null
                || queryFingerprint == null
                || queryFingerprint.isBlank()
                || groundedAnswer == null
                || groundedAnswer.isBlank()
                || observedAt == null) {
            return false;
        }
        Boolean admitted = transactionTemplate.execute(status -> {
            int clusterInserted = jdbcTemplate.update(
                    """
                    INSERT INTO rag_query_memory_cluster (
                        cluster_id,
                        embedding_profile_id,
                        required_access_levels,
                        centroid,
                        observation_count,
                        created_at,
                        updated_at
                    ) VALUES (
                        ?, ?, ?::jsonb, ?::jsonb, ?, clock_timestamp(), clock_timestamp()
                    )
                    ON CONFLICT (cluster_id) DO NOTHING
                    """,
                    cluster.clusterId(),
                    cluster.embeddingProfileId(),
                    writeJson(cluster.requiredAccessLevels().stream().sorted().toList()),
                    writeJson(cluster.centroid()),
                    cluster.observationCount()
            );

            int observationInserted = jdbcTemplate.update(
                    """
                    INSERT INTO rag_query_memory_observation (
                        observation_id,
                        cluster_id,
                        query_fingerprint,
                        grounded_answer,
                        source_refs,
                        observed_at
                    ) VALUES (?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (cluster_id, query_fingerprint) DO NOTHING
                    """,
                    UUID.randomUUID(),
                    cluster.clusterId(),
                    queryFingerprint,
                    groundedAnswer,
                    writeJson(sourceRefs == null ? List.of() : sourceRefs),
                    java.sql.Timestamp.from(observedAt)
            );
            if (observationInserted == 0) {
                jdbcTemplate.update(
                        """
                        UPDATE rag_query_memory_cluster
                        SET refresh_revision = nextval('rag_query_memory_refresh_revision_seq')
                        WHERE cluster_id = ?
                        """,
                        cluster.clusterId()
                );
                return false;
            }

            if (clusterInserted == 0) {
                jdbcTemplate.update(
                        """
                        UPDATE rag_query_memory_cluster
                        SET embedding_profile_id = ?,
                            required_access_levels = ?::jsonb,
                            centroid = ?::jsonb,
                            observation_count = ?,
                            refresh_revision = nextval('rag_query_memory_refresh_revision_seq'),
                            updated_at = clock_timestamp()
                        WHERE cluster_id = ?
                        """,
                        cluster.embeddingProfileId(),
                        writeJson(cluster.requiredAccessLevels().stream().sorted().toList()),
                        writeJson(cluster.centroid()),
                        cluster.observationCount(),
                        cluster.clusterId()
                );
            }

            jdbcTemplate.update(
                    """
                    DELETE FROM rag_query_memory_observation
                    WHERE observation_id IN (
                        SELECT observation_id
                        FROM rag_query_memory_observation
                        WHERE cluster_id = ?
                        ORDER BY observed_at DESC, observation_id DESC
                        OFFSET ?
                    )
                    """,
                    cluster.clusterId(),
                    Math.max(1, maxObservationsPerCluster)
            );

            jdbcTemplate.update(
                    """
                    DELETE FROM rag_query_memory_cluster
                    WHERE cluster_id IN (
                        SELECT cluster_id
                        FROM rag_query_memory_cluster
                        WHERE embedding_profile_id = ?
                        ORDER BY refresh_revision DESC
                        OFFSET ?
                    )
                    """,
                    cluster.embeddingProfileId(),
                    Math.max(32, maxClusters)
            );
            return true;
        });
        return Boolean.TRUE.equals(admitted);
    }

    private StoredCluster mapCluster(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new StoredCluster(
                rs.getObject("cluster_id", UUID.class),
                rs.getString("embedding_profile_id"),
                readScope(rs.getString("required_access_levels")),
                readCentroid(rs.getString("centroid")),
                rs.getInt("observation_count"),
                rs.getLong("refresh_revision"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize semantic query memory", exception);
        }
    }

    private Set<Long> readScope(String json) {
        try {
            Long[] values = objectMapper.readValue(json, Long[].class);
            LinkedHashSet<Long> result = new LinkedHashSet<>();
            Arrays.stream(values)
                    .filter(java.util.Objects::nonNull)
                    .filter(value -> value > 0)
                    .forEach(result::add);
            return Set.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read semantic query memory scope", exception);
        }
    }

    private float[] readCentroid(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read semantic query memory centroid", exception);
        }
    }

    private List<String> readStringList(String json) {
        try {
            String[] values = objectMapper.readValue(json, String[].class);
            return List.copyOf(Arrays.asList(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read semantic query memory sources", exception);
        }
    }

    public record RefreshCursor(long revision) {
        public RefreshCursor {
            if (revision < 0) {
                throw new IllegalArgumentException(
                        "query memory refresh revision must not be negative"
                );
            }
        }
    }

    public record StoredCluster(
            UUID clusterId,
            String embeddingProfileId,
            Set<Long> requiredAccessLevels,
            float[] centroid,
            int observationCount,
            long refreshRevision,
            Instant updatedAt
    ) {
        public StoredCluster(
                UUID clusterId,
                String embeddingProfileId,
                Set<Long> requiredAccessLevels,
                float[] centroid,
                int observationCount,
                Instant updatedAt
        ) {
            this(
                    clusterId,
                    embeddingProfileId,
                    requiredAccessLevels,
                    centroid,
                    observationCount,
                    0L,
                    updatedAt
            );
        }

        public StoredCluster {
            requiredAccessLevels = requiredAccessLevels == null
                    ? Set.of()
                    : Set.copyOf(requiredAccessLevels);
            centroid = centroid == null ? new float[0] : centroid.clone();
            if (refreshRevision < 0) {
                throw new IllegalArgumentException(
                        "refreshRevision must not be negative"
                );
            }
        }

        public RefreshCursor cursor() {
            return new RefreshCursor(refreshRevision);
        }
    }

    public record StoredObservation(
            String groundedAnswer,
            List<String> sourceRefs,
            Instant observedAt
    ) {
        public StoredObservation {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
}
