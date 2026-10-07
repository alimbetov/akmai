package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RagPolicyShadowObservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public RagPolicyShadowObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean save(Observation observation) {
        if (observation == null) {
            return false;
        }
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO rag_policy_shadow_observation (
                    observation_id,
                    policy_type,
                    policy_version,
                    query_fingerprint,
                    source_fingerprint,
                    query_class,
                    plan_changed,
                    execution_status,
                    target_chunk_count,
                    found_chunk_count,
                    target_document_count,
                    found_document_count,
                    latency_ms,
                    created_at
                ) VALUES (?, 'RETRIEVAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (policy_type, policy_version, query_fingerprint)
                DO NOTHING
                """,
                UUID.randomUUID(),
                observation.policyVersion(),
                observation.queryFingerprint(),
                observation.sourceFingerprint(),
                observation.queryClass(),
                observation.planChanged(),
                observation.status().name(),
                observation.targetChunkCount(),
                observation.foundChunkCount(),
                observation.targetDocumentCount(),
                observation.foundDocumentCount(),
                observation.latencyMs(),
                java.sql.Timestamp.from(observation.createdAt())
        );
        return inserted == 1;
    }

    public Summary summarize(String policyVersion, Instant since) {
        return summarize(policyVersion, since, 5_000);
    }

    public Summary summarize(
            String policyVersion,
            Instant since,
            int maxObservations
    ) {
        if (policyVersion == null
                || policyVersion.isBlank()
                || since == null
                || maxObservations <= 0) {
            return Summary.EMPTY;
        }
        return jdbcTemplate.query(
                """
                WITH bounded AS (
                    SELECT *
                    FROM rag_policy_shadow_observation
                    WHERE policy_type = 'RETRIEVAL'
                      AND policy_version = ?
                      AND created_at >= ?
                    ORDER BY created_at DESC, observation_id DESC
                    LIMIT ?
                )
                SELECT count(*) AS observations,
                       count(*) FILTER (WHERE plan_changed) AS changed_plans,
                       count(*) FILTER (WHERE execution_status = 'SUCCESS') AS successes,
                       count(*) FILTER (WHERE execution_status = 'NO_CHANGE') AS no_changes,
                       count(*) FILTER (WHERE execution_status = 'DEGRADED') AS degraded,
                       count(*) FILTER (WHERE execution_status = 'CRITICAL_FAILURE') AS critical_failures,
                       count(*) FILTER (WHERE execution_status = 'FAILED') AS failures,
                       count(DISTINCT source_fingerprint) AS distinct_sources,
                       count(DISTINCT query_class) AS query_classes,
                       COALESCE(sum(target_chunk_count), 0) AS target_chunks,
                       COALESCE(sum(found_chunk_count), 0) AS found_chunks,
                       COALESCE(sum(target_document_count), 0) AS target_documents,
                       COALESCE(sum(found_document_count), 0) AS found_documents,
                       COALESCE(avg(latency_ms), 0) AS average_latency_ms,
                       COALESCE(
                           percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms),
                           0
                       ) AS p95_latency_ms
                FROM bounded
                """,
                rs -> {
                    if (!rs.next()) {
                        return Summary.EMPTY;
                    }
                    long targetChunks = rs.getLong("target_chunks");
                    long targetDocuments = rs.getLong("target_documents");
                    return new Summary(
                            rs.getLong("observations"),
                            rs.getLong("changed_plans"),
                            rs.getLong("successes"),
                            rs.getLong("no_changes"),
                            rs.getLong("degraded"),
                            rs.getLong("critical_failures"),
                            rs.getLong("failures"),
                            rs.getLong("distinct_sources"),
                            rs.getLong("query_classes"),
                            targetChunks,
                            rs.getLong("found_chunks"),
                            targetDocuments,
                            rs.getLong("found_documents"),
                            targetChunks == 0
                                    ? 1.0
                                    : (double) rs.getLong("found_chunks") / targetChunks,
                            targetDocuments == 0
                                    ? 1.0
                                    : (double) rs.getLong("found_documents") / targetDocuments,
                            rs.getDouble("average_latency_ms"),
                            rs.getDouble("p95_latency_ms")
                    );
                },
                policyVersion,
                java.sql.Timestamp.from(since),
                maxObservations
        );
    }

    public enum Status {
        SUCCESS,
        NO_CHANGE,
        DEGRADED,
        CRITICAL_FAILURE,
        FAILED
    }

    public record Observation(
            String policyVersion,
            String queryFingerprint,
            String sourceFingerprint,
            String queryClass,
            boolean planChanged,
            Status status,
            int targetChunkCount,
            int foundChunkCount,
            int targetDocumentCount,
            int foundDocumentCount,
            long latencyMs,
            Instant createdAt
    ) {
        public Observation {
            if (policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalArgumentException("policyVersion is required");
            }
            if (queryFingerprint == null || queryFingerprint.length() != 64) {
                throw new IllegalArgumentException("queryFingerprint must be 64 chars");
            }
            if (sourceFingerprint == null || sourceFingerprint.length() != 64) {
                throw new IllegalArgumentException("sourceFingerprint must be 64 chars");
            }
            queryClass = queryClass == null || queryClass.isBlank()
                    ? "ANALYSIS_UNAVAILABLE"
                    : queryClass;
            status = status == null ? Status.FAILED : status;
            targetChunkCount = Math.max(0, targetChunkCount);
            foundChunkCount = Math.max(0, Math.min(foundChunkCount, targetChunkCount));
            targetDocumentCount = Math.max(0, targetDocumentCount);
            foundDocumentCount = Math.max(
                    0,
                    Math.min(foundDocumentCount, targetDocumentCount)
            );
            latencyMs = Math.max(0, latencyMs);
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
    }

    public record Summary(
            long observations,
            long changedPlans,
            long successes,
            long noChanges,
            long degraded,
            long criticalFailures,
            long failures,
            long distinctSources,
            long queryClasses,
            long targetChunks,
            long foundChunks,
            long targetDocuments,
            long foundDocuments,
            double chunkRetention,
            double documentRetention,
            double averageLatencyMs,
            double p95LatencyMs
    ) {
        private static final Summary EMPTY = new Summary(
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0,
                1.0, 1.0, 0.0, 0.0
        );

        public double failureRate() {
            return observations == 0
                    ? 0.0
                    : (double) (degraded + criticalFailures + failures) / observations;
        }
    }
}
