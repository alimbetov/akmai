package kz.alimbetov.akmai.rag.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class RagPolicyRegistryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public RagPolicyRegistryRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public void registerCandidate(
            RagPolicyType type,
            String version,
            Map<String, Object> configuration
    ) {
        validate(type, version);
        jdbcTemplate.update(
                """
                INSERT INTO rag_policy_registry (
                    policy_type,
                    policy_version,
                    policy_status,
                    configuration_json
                ) VALUES (?, ?, 'CANDIDATE', ?::jsonb)
                ON CONFLICT (policy_type, policy_version) DO NOTHING
                """,
                type.name(),
                version,
                writeJson(configuration == null ? Map.of() : configuration)
        );
    }

    public void attachReports(
            RagPolicyType type,
            String version,
            Map<String, Object> qualityReport,
            Map<String, Object> performanceReport
    ) {
        validate(type, version);
        int updated = jdbcTemplate.update(
                """
                UPDATE rag_policy_registry
                SET quality_report_json = ?::jsonb,
                    performance_report_json = ?::jsonb
                WHERE policy_type = ?
                  AND policy_version = ?
                  AND policy_status IN ('CANDIDATE', 'SHADOW', 'CANARY')
                """,
                writeJson(qualityReport == null ? Map.of() : qualityReport),
                writeJson(performanceReport == null ? Map.of() : performanceReport),
                type.name(),
                version
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Policy is missing or cannot accept evaluation reports"
            );
        }
    }

    public void markShadow(RagPolicyType type, String version) {
        transition(type, version, RagPolicyStatus.CANDIDATE, RagPolicyStatus.SHADOW);
    }

    public void markCanary(RagPolicyType type, String version) {
        transition(type, version, RagPolicyStatus.SHADOW, RagPolicyStatus.CANARY);
    }

    public void reject(RagPolicyType type, String version) {
        validate(type, version);
        int updated = jdbcTemplate.update(
                """
                UPDATE rag_policy_registry
                SET policy_status = 'REJECTED',
                    decided_at = clock_timestamp()
                WHERE policy_type = ?
                  AND policy_version = ?
                  AND policy_status IN ('CANDIDATE', 'SHADOW', 'CANARY')
                """,
                type.name(),
                version
        );
        if (updated != 1) {
            throw new IllegalStateException("Policy cannot be rejected from its current state");
        }
    }

    public void approve(RagPolicyType type, String version) {
        validate(type, version);
        transactionTemplate.executeWithoutResult(status -> {
            List<PolicyRecord> target = lock(type, version);
            if (target.isEmpty()
                    || target.getFirst().status() != RagPolicyStatus.CANARY
                    || target.getFirst().qualityReport().isEmpty()
                    || target.getFirst().performanceReport().isEmpty()) {
                throw new IllegalStateException(
                        "Policy must be CANARY with quality and performance reports before approval"
                );
            }

            jdbcTemplate.update(
                    """
                    UPDATE rag_policy_registry
                    SET policy_status = 'SUPERSEDED',
                        decided_at = clock_timestamp()
                    WHERE policy_type = ?
                      AND policy_status = 'APPROVED'
                    """,
                    type.name()
            );
            int updated = jdbcTemplate.update(
                    """
                    UPDATE rag_policy_registry
                    SET policy_status = 'APPROVED',
                        decided_at = clock_timestamp()
                    WHERE policy_type = ?
                      AND policy_version = ?
                      AND policy_status = 'CANARY'
                    """,
                    type.name(),
                    version
            );
            if (updated != 1) {
                throw new IllegalStateException("Policy approval lost its fencing state");
            }
        });
    }

    public void rollbackTo(RagPolicyType type, String targetVersion) {
        validate(type, targetVersion);
        transactionTemplate.executeWithoutResult(status -> {
            List<PolicyRecord> target = lock(type, targetVersion);
            if (target.isEmpty()
                    || target.getFirst().status() != RagPolicyStatus.SUPERSEDED) {
                throw new IllegalStateException(
                        "Rollback target must be a previously approved SUPERSEDED policy"
                );
            }

            List<PolicyRecord> current = jdbcTemplate.query(
                    """
                    SELECT policy_type,
                           policy_version,
                           policy_status,
                           configuration_json::text,
                           quality_report_json::text,
                           performance_report_json::text,
                           created_at,
                           decided_at
                    FROM rag_policy_registry
                    WHERE policy_type = ?
                      AND policy_status = 'APPROVED'
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> mapRecord(rs),
                    type.name()
            );
            if (current.size() != 1) {
                throw new IllegalStateException(
                        "Rollback requires exactly one current APPROVED policy"
                );
            }

            int demoted = jdbcTemplate.update(
                    """
                    UPDATE rag_policy_registry
                    SET policy_status = 'ROLLED_BACK',
                        decided_at = clock_timestamp()
                    WHERE policy_type = ?
                      AND policy_version = ?
                      AND policy_status = 'APPROVED'
                    """,
                    type.name(),
                    current.getFirst().version()
            );
            int restored = jdbcTemplate.update(
                    """
                    UPDATE rag_policy_registry
                    SET policy_status = 'APPROVED',
                        decided_at = clock_timestamp()
                    WHERE policy_type = ?
                      AND policy_version = ?
                      AND policy_status = 'SUPERSEDED'
                    """,
                    type.name(),
                    targetVersion
            );
            if (demoted != 1 || restored != 1) {
                throw new IllegalStateException("Policy rollback lost its fencing state");
            }
        });
    }

    public Optional<PolicyRecord> approved(RagPolicyType type) {
        return findByStatus(type, RagPolicyStatus.APPROVED);
    }

    public Optional<PolicyRecord> shadow(RagPolicyType type) {
        return findByStatus(type, RagPolicyStatus.SHADOW);
    }

    public Optional<PolicyRecord> canary(RagPolicyType type) {
        return findByStatus(type, RagPolicyStatus.CANARY);
    }

    private Optional<PolicyRecord> findByStatus(
            RagPolicyType type,
            RagPolicyStatus status
    ) {
        if (type == null || status == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                SELECT policy_type,
                       policy_version,
                       policy_status,
                       configuration_json::text,
                       quality_report_json::text,
                       performance_report_json::text,
                       created_at,
                       decided_at
                FROM rag_policy_registry
                WHERE policy_type = ?
                  AND policy_status = ?
                """,
                (rs, rowNum) -> mapRecord(rs),
                type.name(),
                status.name()
        ).stream().findFirst();
    }

    public Optional<PolicyRecord> find(RagPolicyType type, String version) {
        validate(type, version);
        return jdbcTemplate.query(
                """
                SELECT policy_type,
                       policy_version,
                       policy_status,
                       configuration_json::text,
                       quality_report_json::text,
                       performance_report_json::text,
                       created_at,
                       decided_at
                FROM rag_policy_registry
                WHERE policy_type = ?
                  AND policy_version = ?
                """,
                (rs, rowNum) -> mapRecord(rs),
                type.name(),
                version
        ).stream().findFirst();
    }

    private List<PolicyRecord> lock(RagPolicyType type, String version) {
        return jdbcTemplate.query(
                """
                SELECT policy_type,
                       policy_version,
                       policy_status,
                       configuration_json::text,
                       quality_report_json::text,
                       performance_report_json::text,
                       created_at,
                       decided_at
                FROM rag_policy_registry
                WHERE policy_type = ?
                  AND policy_version = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> mapRecord(rs),
                type.name(),
                version
        );
    }

    private void transition(
            RagPolicyType type,
            String version,
            RagPolicyStatus expected,
            RagPolicyStatus target
    ) {
        validate(type, version);
        int updated = jdbcTemplate.update(
                """
                UPDATE rag_policy_registry
                SET policy_status = ?,
                    decided_at = clock_timestamp()
                WHERE policy_type = ?
                  AND policy_version = ?
                  AND policy_status = ?
                """,
                target.name(),
                type.name(),
                version,
                expected.name()
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Policy transition " + expected + " -> " + target + " is not allowed"
            );
        }
    }

    private PolicyRecord mapRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PolicyRecord(
                RagPolicyType.valueOf(rs.getString("policy_type")),
                rs.getString("policy_version"),
                RagPolicyStatus.valueOf(rs.getString("policy_status")),
                readMap(rs.getString("configuration_json")),
                readMap(rs.getString("quality_report_json")),
                readMap(rs.getString("performance_report_json")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at") == null
                        ? null
                        : rs.getTimestamp("decided_at").toInstant()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(json, Map.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read RAG policy JSON", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize RAG policy JSON", exception);
        }
    }

    private void validate(RagPolicyType type, String version) {
        if (type == null || version == null || version.isBlank() || version.length() > 128) {
            throw new IllegalArgumentException("policy type and version are required");
        }
    }

    public record PolicyRecord(
            RagPolicyType type,
            String version,
            RagPolicyStatus status,
            Map<String, Object> configuration,
            Map<String, Object> qualityReport,
            Map<String, Object> performanceReport,
            Instant createdAt,
            Instant decidedAt
    ) {
        public PolicyRecord {
            configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
            qualityReport = qualityReport == null ? Map.of() : Map.copyOf(qualityReport);
            performanceReport = performanceReport == null
                    ? Map.of()
                    : Map.copyOf(performanceReport);
        }
    }
}
