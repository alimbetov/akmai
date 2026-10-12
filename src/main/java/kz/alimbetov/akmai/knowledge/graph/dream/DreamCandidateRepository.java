package kz.alimbetov.akmai.knowledge.graph.dream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.lifecycle.PublishedLifecycleSql;
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

        String sql = """
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
                  AND EXISTS (
                    SELECT 1
                    FROM knowledge_document_lifecycle la
                    JOIN knowledge_document_generation ga
                      ON ga.document_id = la.document_id
                     AND ga.generation = la.published_generation
                     AND ga.access_level = la.access_level
                    WHERE la.document_id = ?
                      AND la.access_level = ?
                      AND la.published_generation = ?
                      AND %s
                )
                  AND EXISTS (
                    SELECT 1
                    FROM knowledge_document_lifecycle lb
                    JOIN knowledge_document_generation gb
                      ON gb.document_id = lb.document_id
                     AND gb.generation = lb.published_generation
                     AND gb.access_level = lb.access_level
                    WHERE lb.document_id = ?
                      AND lb.access_level = ?
                      AND lb.published_generation = ?
                      AND %s
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
                    retirement_reason = CASE
                        WHEN EXCLUDED.state = 'ACTIVE' THEN NULL
                        ELSE knowledge_chunk_dream_candidate.retirement_reason
                    END,
                    retired_at = CASE
                        WHEN EXCLUDED.state = 'ACTIVE' THEN NULL
                        ELSE knowledge_chunk_dream_candidate.retired_at
                    END,
                    last_seen_at = CASE
                        WHEN EXCLUDED.mutual_knn
                        THEN EXCLUDED.last_seen_at
                        ELSE knowledge_chunk_dream_candidate.last_seen_at
                    END,
                    last_verified_at = EXCLUDED.last_verified_at,
                    updated_at = clock_timestamp()
                """.formatted(
                        PublishedLifecycleSql.publishedGenerationEligible("la", "ga"),
                        PublishedLifecycleSql.publishedGenerationEligible("lb", "gb")
                );

        int changed = jdbcTemplate.update(
                sql,
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
                authority.fencingToken(),
                pair.first().documentId(),
                pair.first().accessLevel(),
                pair.first().generation(),
                pair.second().documentId(),
                pair.second().accessLevel(),
                pair.second().generation()
        );
        if (changed != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream candidate observation rejected by lease or lifecycle fencing"
            );
        }
    }

    /**
     * Returns lifecycle-valid ACTIVE pairs currently involving the source under
     * the exact Dream policy epoch. Stored stale rows may remain until retirement
     * or compaction, but they are never returned as usable Dream state.
     */
    public List<DreamPair> findActivePairsForSource(
            int graphVersion,
            String semanticPolicyFingerprint,
            ChunkGraphNode source
    ) {
        if (graphVersion <= 0 || source == null) {
            throw new IllegalArgumentException(
                    "Dream active-pair lookup identity is required"
            );
        }
        String fingerprint = requireText(
                "semanticPolicyFingerprint",
                semanticPolicyFingerprint
        );
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "semanticPolicyFingerprint must be SHA-256 hex"
            );
        }

        String sql = """
                SELECT c.access_level,
                       c.node_a_document_id,
                       c.node_a_generation,
                       c.node_a_chunk_id,
                       c.node_b_document_id,
                       c.node_b_generation,
                       c.node_b_chunk_id
                FROM knowledge_chunk_dream_candidate c
                JOIN knowledge_document_lifecycle la
                  ON la.document_id = c.node_a_document_id
                 AND la.access_level = c.access_level
                 AND la.published_generation = c.node_a_generation
                JOIN knowledge_document_generation ga
                  ON ga.document_id = c.node_a_document_id
                 AND ga.generation = c.node_a_generation
                 AND ga.access_level = c.access_level
                JOIN knowledge_document_lifecycle lb
                  ON lb.document_id = c.node_b_document_id
                 AND lb.access_level = c.access_level
                 AND lb.published_generation = c.node_b_generation
                JOIN knowledge_document_generation gb
                  ON gb.document_id = c.node_b_document_id
                 AND gb.generation = c.node_b_generation
                 AND gb.access_level = c.access_level
                WHERE c.graph_version = ?
                  AND c.semantic_policy_fingerprint = ?
                  AND c.state = 'ACTIVE'
                  AND c.access_level = ?
                  AND %s
                  AND %s
                  AND (
                      (
                          c.node_a_document_id = ?
                          AND c.node_a_generation = ?
                          AND c.node_a_chunk_id = ?
                      ) OR (
                          c.node_b_document_id = ?
                          AND c.node_b_generation = ?
                          AND c.node_b_chunk_id = ?
                      )
                  )
                """.formatted(
                        PublishedLifecycleSql.publishedGenerationEligible("la", "ga"),
                        PublishedLifecycleSql.publishedGenerationEligible("lb", "gb")
                );

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> DreamPair.of(
                        new ChunkGraphNode(
                                rs.getLong("access_level"),
                                rs.getString("node_a_document_id"),
                                rs.getLong("node_a_generation"),
                                rs.getString("node_a_chunk_id")
                        ),
                        new ChunkGraphNode(
                                rs.getLong("access_level"),
                                rs.getString("node_b_document_id"),
                                rs.getLong("node_b_generation"),
                                rs.getString("node_b_chunk_id")
                        )
                ),
                graphVersion,
                fingerprint,
                source.accessLevel(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                source.documentId(),
                source.generation(),
                source.chunkId()
        );
    }

    /**
     * Retires an existing ACTIVE observation without manufacturing a new
     * candidate row. The data-modifying CTE distinguishes a missing row from a
     * lost lease: no existing candidate is a safe no-op, while lost authority is
     * always fatal.
     */
    public boolean markStaleIfActive(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            UUID runId,
            String reason,
            Instant observedAt
    ) {
        if (authority == null || pair == null || runId == null
                || observedAt == null) {
            throw new IllegalArgumentException(
                    "Dream stale-candidate identity is required"
            );
        }
        String retirementReason = requireText("reason", reason);
        StaleResult result = jdbcTemplate.queryForObject(
                """
                WITH live_authority AS (
                    SELECT 1
                    FROM adaptive_graph_dream_lease lease
                    WHERE lease.graph_version = ?
                      AND lease.semantic_policy_fingerprint = ?
                      AND lease.owner_id = ?
                      AND lease.fencing_token = ?
                      AND lease.lease_until > clock_timestamp()
                ), updated AS (
                    UPDATE knowledge_chunk_dream_candidate candidate
                    SET state = 'STALE',
                        mutual_knn = FALSE,
                        confidence = 0,
                        positive_streak = 0,
                        negative_streak = 1,
                        last_verified_run_id = ?,
                        retirement_reason = ?,
                        last_verified_at = ?,
                        retired_at = COALESCE(candidate.retired_at, ?),
                        updated_at = clock_timestamp()
                    WHERE candidate.access_level = ?
                      AND candidate.node_a_document_id = ?
                      AND candidate.node_a_generation = ?
                      AND candidate.node_a_chunk_id = ?
                      AND candidate.node_b_document_id = ?
                      AND candidate.node_b_generation = ?
                      AND candidate.node_b_chunk_id = ?
                      AND candidate.graph_version = ?
                      AND candidate.semantic_policy_fingerprint = ?
                      AND candidate.state = 'ACTIVE'
                      AND EXISTS (SELECT 1 FROM live_authority)
                    RETURNING 1
                )
                SELECT EXISTS(SELECT 1 FROM live_authority) AS owned,
                       EXISTS(SELECT 1 FROM updated) AS changed
                """,
                (rs, rowNum) -> new StaleResult(
                        rs.getBoolean("owned"),
                        rs.getBoolean("changed")
                ),
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken(),
                runId,
                retirementReason,
                Timestamp.from(observedAt),
                Timestamp.from(observedAt),
                pair.first().accessLevel(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                authority.graphVersion(),
                authority.policyFingerprint()
        );
        if (result == null || !result.owned()) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream candidate retirement rejected by fencing"
            );
        }
        return result.changed();
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

    private record StaleResult(boolean owned, boolean changed) {
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
