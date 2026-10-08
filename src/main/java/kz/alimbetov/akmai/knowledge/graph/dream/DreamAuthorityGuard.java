package kz.alimbetov.akmai.knowledge.graph.dream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Transaction-local fencing validation for DREAM graph mutations.
 */
@Component
public class DreamAuthorityGuard {

    private final JdbcTemplate jdbcTemplate;

    public DreamAuthorityGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireOwned(DreamLeaseManager.Authority authority) {
        if (authority == null) {
            throw new IllegalArgumentException("Dream authority must not be null");
        }
        Boolean owned = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM adaptive_graph_dream_lease
                    WHERE graph_version = ?
                      AND semantic_policy_fingerprint = ?
                      AND owner_id = ?
                      AND fencing_token = ?
                      AND lease_until > clock_timestamp()
                )
                """,
                Boolean.class,
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (!Boolean.TRUE.equals(owned)) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream graph mutation rejected by fencing"
            );
        }
    }
}
