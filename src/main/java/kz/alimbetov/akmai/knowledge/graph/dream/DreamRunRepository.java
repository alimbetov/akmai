package kz.alimbetov.akmai.knowledge.graph.dream;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DreamRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public DreamRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID start(
            DreamLeaseManager.Authority authority,
            DreamPolicyResolver.ResolvedDreamPolicy policy
    ) {
        UUID runId = UUID.randomUUID();
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO adaptive_graph_dream_run (
                    run_id,
                    owner_id,
                    fencing_token,
                    graph_version,
                    semantic_policy_version,
                    semantic_policy_fingerprint,
                    status
                )
                SELECT ?, ?, ?, ?, ?, ?, 'RUNNING'
                WHERE EXISTS (
                    SELECT 1
                    FROM adaptive_graph_dream_lease lease
                    WHERE lease.graph_version = ?
                      AND lease.semantic_policy_fingerprint = ?
                      AND lease.owner_id = ?
                      AND lease.fencing_token = ?
                      AND lease.lease_until > clock_timestamp()
                )
                """,
                runId,
                authority.ownerId(),
                authority.fencingToken(),
                policy.graphVersion(),
                policy.semanticPolicyVersion(),
                policy.fingerprint(),
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (inserted != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Cannot start Dream run without live authority"
            );
        }
        return runId;
    }

    public void finishAuthoritative(
            UUID runId,
            DreamLeaseManager.Authority authority,
            RunOutcome outcome,
            DreamBudget.Snapshot budget,
            String stopReason
    ) {
        if (outcome == RunOutcome.LOST_OWNERSHIP) {
            throw new IllegalArgumentException(
                    "Use markLostOwnership for lost authority"
            );
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE adaptive_graph_dream_run run
                SET status = ?,
                    completed_at = clock_timestamp(),
                    forward_ann_queries = ?,
                    reverse_ann_queries = ?,
                    db_rows_touched = ?,
                    stop_reason = ?,
                    completed_at = clock_timestamp()
                WHERE run.run_id = ?
                  AND run.owner_id = ?
                  AND run.fencing_token = ?
                  AND EXISTS (
                      SELECT 1
                      FROM adaptive_graph_dream_lease lease
                      WHERE lease.graph_version = run.graph_version
                        AND lease.semantic_policy_fingerprint = run.semantic_policy_fingerprint
                        AND lease.owner_id = ?
                        AND lease.fencing_token = ?
                        AND lease.lease_until > clock_timestamp()
                  )
                """,
                outcome.name(),
                Math.max(0, budget.annQueries() - budget.reverseAnnQueries()),
                budget.reverseAnnQueries(),
                budget.dbRowsTouched(),
                stopReason,
                runId,
                authority.ownerId(),
                authority.fencingToken(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (updated != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream run finalization rejected by fencing"
            );
        }
    }

    public void markLostOwnership(
            UUID runId,
            DreamLeaseManager.Authority authority,
            DreamBudget.Snapshot budget
    ) {
        jdbcTemplate.update(
                """
                UPDATE adaptive_graph_dream_run
                SET status = 'LOST_OWNERSHIP',
                    completed_at = clock_timestamp(),
                    forward_ann_queries = ?,
                    reverse_ann_queries = ?,
                    db_rows_touched = ?,
                    stop_reason = 'LEASE_LOST'
                WHERE run_id = ?
                  AND owner_id = ?
                  AND fencing_token = ?
                  AND status = 'RUNNING'
                """,
                Math.max(0, budget.annQueries() - budget.reverseAnnQueries()),
                budget.reverseAnnQueries(),
                budget.dbRowsTouched(),
                runId,
                authority.ownerId(),
                authority.fencingToken()
        );
    }

    public enum RunOutcome {
        SUCCEEDED,
        PARTIAL_BUDGET,
        FAILED,
        LOST_OWNERSHIP,
        CANCELLED
    }
}
