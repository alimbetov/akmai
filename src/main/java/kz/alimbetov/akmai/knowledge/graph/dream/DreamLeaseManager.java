package kz.alimbetov.akmai.knowledge.graph.dream;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL-time lease with fencing. Expired owners cannot renew or mutate
 * authoritative Dream state using an old token.
 */
@Component
public class DreamLeaseManager {

    private final JdbcTemplate jdbcTemplate;
    private final AdaptiveGraphProperties properties;
    private final String ownerId;

    public DreamLeaseManager(
            JdbcTemplate jdbcTemplate,
            AdaptiveGraphProperties properties
    ) {
        this(jdbcTemplate, properties, defaultOwnerId());
    }

    DreamLeaseManager(
            JdbcTemplate jdbcTemplate,
            AdaptiveGraphProperties properties,
            String ownerId
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.ownerId = requireText("ownerId", ownerId);
    }

    public Optional<Authority> tryAcquire(
            int graphVersion,
            String policyFingerprint
    ) {
        requireIdentity(graphVersion, policyFingerprint);
        Duration lease = properties.dream().leaseDuration();
        List<Long> tokens = jdbcTemplate.query(
                """
                INSERT INTO adaptive_graph_dream_lease (
                    graph_version,
                    semantic_policy_fingerprint,
                    owner_id,
                    lease_until,
                    fencing_token,
                    updated_at
                ) VALUES (
                    ?, ?, ?,
                    clock_timestamp() + (? * interval '1 millisecond'),
                    1,
                    clock_timestamp()
                )
                ON CONFLICT (
                    graph_version,
                    semantic_policy_fingerprint
                ) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    lease_until = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    fencing_token = adaptive_graph_dream_lease.fencing_token + 1,
                    updated_at = clock_timestamp()
                WHERE adaptive_graph_dream_lease.lease_until IS NULL
                   OR adaptive_graph_dream_lease.lease_until <= clock_timestamp()
                RETURNING fencing_token
                """,
                (rs, rowNum) -> rs.getLong(1),
                graphVersion,
                policyFingerprint,
                ownerId,
                lease.toMillis(),
                lease.toMillis()
        );
        return tokens.stream()
                .findFirst()
                .map(token -> new Authority(
                        graphVersion,
                        policyFingerprint,
                        ownerId,
                        token
                ));
    }

    public void renew(Authority authority) {
        requireAuthority(authority);
        int updated = jdbcTemplate.update(
                """
                UPDATE adaptive_graph_dream_lease
                SET lease_until = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE graph_version = ?
                  AND semantic_policy_fingerprint = ?
                  AND owner_id = ?
                  AND fencing_token = ?
                  AND lease_until > clock_timestamp()
                """,
                properties.dream().leaseDuration().toMillis(),
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (updated != 1) {
            throw new LostDreamAuthorityException(
                    "Dream lease is expired or no longer owned"
            );
        }
    }

    public boolean isOwned(Authority authority) {
        requireAuthority(authority);
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM adaptive_graph_dream_lease
                WHERE graph_version = ?
                  AND semantic_policy_fingerprint = ?
                  AND owner_id = ?
                  AND fencing_token = ?
                  AND lease_until > clock_timestamp()
                """,
                Integer.class,
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        return count != null && count == 1;
    }

    public void release(Authority authority) {
        requireAuthority(authority);
        jdbcTemplate.update(
                """
                UPDATE adaptive_graph_dream_lease
                SET owner_id = NULL,
                    lease_until = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE graph_version = ?
                  AND semantic_policy_fingerprint = ?
                  AND owner_id = ?
                  AND fencing_token = ?
                """,
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
    }

    private void requireAuthority(Authority authority) {
        if (authority == null) {
            throw new IllegalArgumentException("Dream authority must not be null");
        }
        requireIdentity(authority.graphVersion(), authority.policyFingerprint());
        requireText("ownerId", authority.ownerId());
        if (authority.fencingToken() <= 0) {
            throw new IllegalArgumentException("Dream fencing token must be positive");
        }
    }

    private void requireIdentity(int graphVersion, String policyFingerprint) {
        if (graphVersion <= 0) {
            throw new IllegalArgumentException("graphVersion must be positive");
        }
        String fingerprint = requireText("policyFingerprint", policyFingerprint);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("policyFingerprint must be lowercase SHA-256 hex");
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String defaultOwnerId() {
        String pod = System.getenv("HOSTNAME");
        String prefix = pod == null || pod.isBlank() ? "akmai" : pod;
        return prefix + "/" + UUID.randomUUID();
    }

    public record Authority(
            int graphVersion,
            String policyFingerprint,
            String ownerId,
            long fencingToken
    ) {
    }

    public static final class LostDreamAuthorityException
            extends IllegalStateException {
        public LostDreamAuthorityException(String message) {
            super(message);
        }
    }
}
