package kz.alimbetov.akmai.rag.policy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RagPolicyCanaryObservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public RagPolicyCanaryObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean save(Observation observation) {
        if (observation == null
                || observation.policyVersion() == null
                || observation.policyVersion().isBlank()
                || observation.requestId() == null
                || observation.cohort() == null) {
            return false;
        }
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO rag_policy_canary_observation (
                    policy_version,
                    request_id,
                    cohort,
                    source_fingerprint,
                    query_class,
                    answer_status,
                    grounding_status,
                    degraded,
                    critical_failure,
                    total_latency_ms,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (policy_version, request_id) DO NOTHING
                """,
                observation.policyVersion(),
                observation.requestId(),
                observation.cohort().name(),
                safe(observation.sourceFingerprint()),
                safe(observation.queryClass()),
                safe(observation.answerStatus()),
                safe(observation.groundingStatus()),
                observation.degraded(),
                observation.criticalFailure(),
                Math.max(0, observation.totalLatencyMs()),
                Timestamp.from(observation.createdAt() == null
                        ? Instant.now()
                        : observation.createdAt())
        );
        return inserted == 1;
    }

    public Summary summarize(
            String policyVersion,
            Instant since,
            int maxObservations
    ) {
        if (policyVersion == null || policyVersion.isBlank()) {
            return Summary.empty();
        }
        Instant lowerBound = since == null ? Instant.EPOCH : since;
        int limit = Math.max(1, maxObservations);
        List<Observation> observations = jdbcTemplate.query(
                """
                SELECT policy_version,
                       request_id,
                       cohort,
                       source_fingerprint,
                       query_class,
                       answer_status,
                       grounding_status,
                       degraded,
                       critical_failure,
                       total_latency_ms,
                       created_at
                FROM rag_policy_canary_observation
                WHERE policy_version = ?
                  AND created_at >= ?
                ORDER BY id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new Observation(
                        rs.getString("policy_version"),
                        UUID.fromString(rs.getString("request_id")),
                        CanaryRoutingObservationStore.Cohort.valueOf(
                                rs.getString("cohort")
                        ),
                        rs.getString("source_fingerprint"),
                        rs.getString("query_class"),
                        rs.getString("answer_status"),
                        rs.getString("grounding_status"),
                        rs.getBoolean("degraded"),
                        rs.getBoolean("critical_failure"),
                        rs.getLong("total_latency_ms"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                policyVersion,
                Timestamp.from(lowerBound),
                limit
        );
        return new Summary(
                cohort(observations, CanaryRoutingObservationStore.Cohort.CANARY),
                cohort(observations, CanaryRoutingObservationStore.Cohort.CONTROL)
        );
    }

    private CohortSummary cohort(
            List<Observation> observations,
            CanaryRoutingObservationStore.Cohort cohort
    ) {
        List<Observation> values = observations.stream()
                .filter(value -> value.cohort() == cohort)
                .toList();
        if (values.isEmpty()) {
            return CohortSummary.empty();
        }
        Set<String> sources = new HashSet<>();
        Set<String> queryClasses = new HashSet<>();
        long grounded = 0;
        long unavailable = 0;
        long critical = 0;
        long degraded = 0;
        List<Long> latencies = new ArrayList<>(values.size());
        for (Observation value : values) {
            if (value.sourceFingerprint() != null
                    && !value.sourceFingerprint().isBlank()) {
                sources.add(value.sourceFingerprint());
            }
            if (value.queryClass() != null && !value.queryClass().isBlank()) {
                queryClasses.add(value.queryClass());
            }
            if ("GROUNDED".equals(value.answerStatus())
                    && "SUPPORTED".equals(value.groundingStatus())) {
                grounded++;
            }
            if ("UNAVAILABLE".equals(value.answerStatus())) {
                unavailable++;
            }
            if (value.criticalFailure()) {
                critical++;
            }
            if (value.degraded()) {
                degraded++;
            }
            latencies.add(Math.max(0, value.totalLatencyMs()));
        }
        long count = values.size();
        return new CohortSummary(
                count,
                sources.size(),
                Set.copyOf(queryClasses),
                rate(grounded, count),
                rate(unavailable, count),
                rate(critical, count),
                rate(degraded, count),
                percentile95(latencies)
        );
    }

    private double rate(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private long percentile95(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = Math.max(
                0,
                (int) Math.ceil(sorted.size() * 0.95) - 1
        );
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record Observation(
            String policyVersion,
            UUID requestId,
            CanaryRoutingObservationStore.Cohort cohort,
            String sourceFingerprint,
            String queryClass,
            String answerStatus,
            String groundingStatus,
            boolean degraded,
            boolean criticalFailure,
            long totalLatencyMs,
            Instant createdAt
    ) {
    }

    public record Summary(
            CohortSummary canary,
            CohortSummary control
    ) {
        public static Summary empty() {
            return new Summary(CohortSummary.empty(), CohortSummary.empty());
        }
    }

    public record CohortSummary(
            long samples,
            long distinctSources,
            Set<String> queryClasses,
            double groundedRate,
            double unavailableRate,
            double criticalFailureRate,
            double degradedRate,
            long p95LatencyMs
    ) {
        public CohortSummary {
            queryClasses = queryClasses == null ? Set.of() : Set.copyOf(queryClasses);
        }

        public static CohortSummary empty() {
            return new CohortSummary(
                    0,
                    0,
                    Set.of(),
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0
            );
        }
    }
}
