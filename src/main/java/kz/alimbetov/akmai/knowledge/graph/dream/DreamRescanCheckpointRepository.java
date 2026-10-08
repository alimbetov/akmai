package kz.alimbetov.akmai.knowledge.graph.dream;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fenced compare-and-set cursor for bounded deterministic rescan rotation. */
@Repository
public class DreamRescanCheckpointRepository {

    private final JdbcTemplate jdbcTemplate;

    public DreamRescanCheckpointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void advance(
            DreamLeaseManager.Authority authority,
            String expectedEncoded,
            DreamRescanCursor next,
            UUID completedRunId
    ) {
        if (authority == null || completedRunId == null) {
            throw new IllegalArgumentException(
                    "Dream rescan checkpoint identity is required"
            );
        }
        String nextEncoded = next == null ? null : next.encode();
        int updated = jdbcTemplate.update(
                """
                UPDATE adaptive_graph_dream_checkpoint checkpoint
                SET rescan_cursor = ?,
                    last_successful_run_id = ?,
                    last_completed_at = clock_timestamp(),
                    fencing_token = ?,
                    updated_at = clock_timestamp()
                WHERE checkpoint.graph_version = ?
                  AND checkpoint.semantic_policy_fingerprint = ?
                  AND checkpoint.rescan_cursor IS NOT DISTINCT FROM ?
                  AND EXISTS (
                      SELECT 1
                      FROM adaptive_graph_dream_lease lease
                      WHERE lease.graph_version = checkpoint.graph_version
                        AND lease.semantic_policy_fingerprint = checkpoint.semantic_policy_fingerprint
                        AND lease.owner_id = ?
                        AND lease.fencing_token = ?
                        AND lease.lease_until > clock_timestamp()
                  )
                """,
                nextEncoded,
                completedRunId,
                authority.fencingToken(),
                authority.graphVersion(),
                authority.policyFingerprint(),
                expectedEncoded,
                authority.ownerId(),
                authority.fencingToken()
        );
        if (updated != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream rescan checkpoint CAS failed or authority was lost"
            );
        }
    }
}
