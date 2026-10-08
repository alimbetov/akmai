package kz.alimbetov.akmai.knowledge.graph.dream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists canonical Dream discovery observations. DREAM-4B discovery is
 * replay-neutral: it records current semantic evidence but does not accumulate
 * verification streaks. Stateful streak accumulation belongs to DREAM-6 where
 * observations receive their own deterministic verification identity.
 */
@Repository
public class DreamCandidateRepository {

    private final JdbcTemplate jdbcTemplate;

    public DreamCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void observe(
            DreamLeaseManager.Authority authority,
            Observation observation
    ) {
        if (authority == null) {
            throw new IllegalArgumentException("Dream authority is required");
        }
        requireObservation(observation);
        if (authority.graphVersion() != observation.graphVersion()
                || !authority.policyFingerprint().equals(
                        observation.semanticPolicyFingerprint()
                )) {
            throw new IllegalArgumentException(
                    "Dream observation policy does not match lease authority"
            );
        }

        DreamPair pair = observation.pair();
        boolean positive =
                observation.outcome() == ObservationOutcome.POSITIVE;
        Timestamp observedAt = Timestamp.from(observation.observedAt());

        int changed = jdbcTemplate.update(
                """
                INSERT INTO knowledge_chunk_dream_candidate (
                    access_level,
                    node_a_document_id,
                    node_a_generation,
                    node_a_chunk_id,
                    node_b_document_id,
                    node_b_generation,
                    node_b_chunk_id,
                    graph_version,
                    semantic_policy_version,
                    semantic_policy_fingerprint,
                    state,
                    forward_similarity,
                    reverse_similarity,
                    forward_rank,
                    reverse_rank,
                    mutual_knn,
                    confidence,
                    positive_streak,
                    negative_streak,
                    embedding_profile_id,
                    discovery_run_id,
                    last_verified_run_id,
                    discovery_reason,
                    first_seen_at,
                    last_seen_at,
                    last_verified_at,
                    updated_at
                )
                SELECT
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, clock_timestamp()
                WHERE EXISTS (
                    SELECT 1
                    FROM adaptive_graph_dream_lease lease
                    WHERE lease.graph_version = ?
                      AND lease.semantic_policy_fingerprint = ?
                      AND lease.owner_id = ?
                      AND lease.fencing_token = ?
                      AND lease.lease_until > clock_timestamp()
                )
                ON CONFLICT (
                    access_level,
                    node_a_document_id,
                    node_a_generation,
                    node_a_chunk_id,
                    node_b_document_id,
                    node_b_generation,
                    node_b_chunk_id,
                    graph_version,
                    semantic_policy_fingerprint
                ) DO UPDATE SET
                    semantic_policy_version = EXCLUDED.semantic_policy_version,
                    state = EXCLUDED.state,
                    forward_similarity = EXCLUDED.forward_similarity,
                    reverse_similarity = EXCLUDED.reverse_similarity,
                    forward_rank = EXCLUDED.forward_rank,
                    reverse_rank = EXCLUDED.reverse_rank,
                    mutual_knn = EXCLUDED.mutual_knn,
                    confidence = EXCLUDED.confidence,
                    positive_streak = EXCLUDED.positive_streak,
                    negative_streak = EXCLUDED.negative_streak,
                    embedding_profile_id = EXCLUDED.embedding_profile_id,
                    last_verified_run_id = EXCLUDED.last_verified_run_id,
                    last_seen_at = CASE
                        WHEN EXCLUDED.mutual_knn
                        THEN EXCLUDED.last_seen_at
                        ELSE knowledge_chunk_dream_candidate.last_seen_at
                    END,
                    last_verified_at = EXCLUDED.last_verified_at,
                    updated_at = clock_timestamp()
                """,
                pair.first().accessLevel(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                observation.graphVersion(),
                observation.semanticPolicyVersion(),
                observation.semanticPolicyFingerprint(),
                observation.state().name(),
                observation.forwardSimilarity(),
                observation.reverseSimilarity(),
                observation.forwardRank(),
                observation.reverseRank(),
                observation.mutualKnn(),
                observation.confidence(),
                positive ? 1 : 0,
                positive ? 0 : 1,
                observation.embeddingProfileId(),
                observation.runId(),
                observation.runId(),
                observation.discoveryReason(),
                observedAt,
                observedAt,
                observedAt,
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (changed != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream candidate observation rejected by fencing"
            );
        }
    }

    private void requireObservation(Observation value) {
        if (value == null || value.pair() == null || value.runId() == null) {
            throw new IllegalArgumentException(
                    "Dream observation identity is required"
            );
        }
        if (value.graphVersion() <= 0) {
            throw new IllegalArgumentException("graphVersion must be positive");
        }
        requireText("semanticPolicyVersion", value.semanticPolicyVersion());
        String fingerprint = requireText(
                "semanticPolicyFingerprint",
                value.semanticPolicyFingerprint()
        );
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "semanticPolicyFingerprint must be SHA-256 hex"
            );
        }
        requireText("embeddingProfileId", value.embeddingProfileId());
        if (value.state() == null || value.outcome() == null) {
            throw new IllegalArgumentException(
                    "Dream observation state/outcome is required"
            );
        }
        bounded("forwardSimilarity", value.forwardSimilarity());
        bounded("reverseSimilarity", value.reverseSimilarity());
        bounded("confidence", value.confidence());
        if (value.forwardRank() <= 0 || value.reverseRank() <= 0) {
            throw new IllegalArgumentException("Dream ranks must be positive");
        }
        if (value.observedAt() == null) {
            throw new IllegalArgumentException("observedAt is required");
        }
        if (value.mutualKnn()
                != (value.outcome() == ObservationOutcome.POSITIVE)) {
            throw new IllegalArgumentException("mutualKnn and outcome disagree");
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void bounded(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }

    public enum CandidateState {
        CANDIDATE,
        ACTIVE,
        STALE,
        REJECTED
    }

    public enum ObservationOutcome {
        POSITIVE,
        NEGATIVE_SEMANTIC
    }

    public record Observation(
            DreamPair pair,
            int graphVersion,
            String semanticPolicyVersion,
            String semanticPolicyFingerprint,
            CandidateState state,
            double forwardSimilarity,
            double reverseSimilarity,
            int forwardRank,
            int reverseRank,
            boolean mutualKnn,
            double confidence,
            String embeddingProfileId,
            UUID runId,
            String discoveryReason,
            ObservationOutcome outcome,
            Instant observedAt
    ) {
    }
}
