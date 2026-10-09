package kz.alimbetov.akmai.knowledge.graph.dream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.graph.GraphMutationLocks;
import kz.alimbetov.akmai.knowledge.graph.JdbcTimeouts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restricted DREAM-5 mutation boundary. It may materialize or retire a semantic
 * prior only; learned evidence counters remain authoritative to online learning.
 */
@Component
public class SemanticGraphPriorWriter {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final DreamRuntimeSwitches switches;
    private final AdaptiveGraphProperties graphProperties;
    private final SemanticMemoryProperties semanticMemoryProperties;
    private final GraphMutationLocks mutationLocks;
    private final DreamCandidateRepository candidateRepository;

    public SemanticGraphPriorWriter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            DreamRuntimeSwitches switches,
            AdaptiveGraphProperties graphProperties,
            SemanticMemoryProperties semanticMemoryProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.switches = switches;
        this.graphProperties = graphProperties;
        this.semanticMemoryProperties = semanticMemoryProperties;
        this.mutationLocks = new GraphMutationLocks(jdbcTemplate);
        this.candidateRepository = new DreamCandidateRepository(jdbcTemplate);
    }

    public ApplyResult applyCandidate(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            double semanticSimilarity,
            Instant observedAt
    ) {
        if (!switches.applyEnabled()) {
            return ApplyResult.APPLY_DISABLED;
        }
        if (authority == null || pair == null || observedAt == null) {
            throw new IllegalArgumentException("Dream apply identity is required");
        }
        if (!Double.isFinite(semanticSimilarity)
                || semanticSimilarity < 0
                || semanticSimilarity > 1) {
            throw new IllegalArgumentException(
                    "semanticSimilarity must be in [0, 1]"
            );
        }
        requireGraphVersion(authority);

        TransactionTemplate transaction = boundedTransaction();
        ApplyResult result = transaction.execute(status -> applyInTransaction(
                authority,
                pair,
                semanticSimilarity,
                observedAt
        ));
        return result == null ? ApplyResult.REJECTED : result;
    }

    /**
     * Atomically transitions an ACTIVE Dream candidate to STALE and, when the
     * production apply gate is enabled, retires its graph-side semantic prior.
     * The graph relation itself and all learned counters are preserved.
     */
    public RetirementResult retireCandidate(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            UUID runId,
            String reason,
            Instant observedAt,
            DreamBudget budget
    ) {
        if (authority == null || pair == null || runId == null
                || reason == null || reason.isBlank()
                || observedAt == null || budget == null) {
            throw new IllegalArgumentException(
                    "Dream retirement identity is required"
            );
        }
        requireGraphVersion(authority);

        boolean retireGraphPrior = switches.applyEnabled();
        int reservedRows = retireGraphPrior ? 3 : 1;
        budget.addDbRows(reservedRows);
        int durableRows = 0;
        try {
            TransactionTemplate transaction = boundedTransaction();
            RetirementResult result = transaction.execute(status -> {
                if (retireGraphPrior) {
                    mutationLocks.lockRetirementNodes(
                            List.of(pair.first(), pair.second())
                    );
                }

                boolean candidateChanged = candidateRepository.markStaleIfActive(
                        authority,
                        pair,
                        runId,
                        reason,
                        observedAt
                );
                if (!candidateChanged) {
                    return RetirementResult.NO_CHANGE;
                }
                if (!retireGraphPrior) {
                    return RetirementResult.CANDIDATE_ONLY;
                }

                int graphRows = retirePriorInTransaction(authority, pair);
                return graphRows == 2
                        ? RetirementResult.CANDIDATE_AND_PRIOR
                        : RetirementResult.CANDIDATE_ONLY;
            });
            RetirementResult resolved = result == null
                    ? RetirementResult.NO_CHANGE
                    : result;
            durableRows = resolved.durableRows();
            return resolved;
        } finally {
            int unusedRows = reservedRows - durableRows;
            if (unusedRows > 0) {
                budget.releaseDbRows(unusedRows);
            }
        }
    }

    private ApplyResult applyInTransaction(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            double semanticSimilarity,
            Instant observedAt
    ) {
        if (!switches.applyEnabled()) {
            return ApplyResult.APPLY_DISABLED;
        }

        mutationLocks.lockEligiblePublishedNodes(
                List.of(pair.first(), pair.second())
        );

        PairState state = pairState(pair, authority.graphVersion());
        int maxSemanticDegree = semanticMemoryProperties.getMaxEdgesPerChunk();
        if (!state.existingPair()
                && (state.firstDegree() >= maxSemanticDegree
                || state.secondDegree() >= maxSemanticDegree)) {
            return ApplyResult.DEGREE_LIMIT;
        }

        upsertPair(
                authority,
                pair,
                semanticSimilarity,
                observedAt
        );
        return state.existingPair() ? ApplyResult.REFRESHED : ApplyResult.APPLIED;
    }

    private TransactionTemplate boundedTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(JdbcTimeouts.toSeconds(
                graphProperties.dream().transactionTimeout()
        ));
        return transaction;
    }

    private void requireGraphVersion(DreamLeaseManager.Authority authority) {
        if (authority.graphVersion() != graphProperties.graphVersion()) {
            throw new IllegalArgumentException(
                    "Dream apply graph version does not match configured graph version"
            );
        }
    }

    /**
     * Reads pair existence and both directional semantic degrees in one round trip.
     * Graph node locks are already held, so these values remain stable until the
     * transaction commits.
     */
    private PairState pairState(DreamPair pair, int graphVersion) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND graph_version = ?
                          AND (
                              (
                                  source_document_id = ?
                                  AND source_generation = ?
                                  AND source_chunk_id = ?
                                  AND target_document_id = ?
                                  AND target_generation = ?
                                  AND target_chunk_id = ?
                              )
                              OR
                              (
                                  source_document_id = ?
                                  AND source_generation = ?
                                  AND source_chunk_id = ?
                                  AND target_document_id = ?
                                  AND target_generation = ?
                                  AND target_chunk_id = ?
                              )
                          )
                    ) AS existing_pair,
                    (
                        SELECT count(*)
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND graph_version = ?
                          AND semantic_similarity IS NOT NULL
                          AND band <> 'DECAYED'
                    ) AS first_degree,
                    (
                        SELECT count(*)
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND graph_version = ?
                          AND semantic_similarity IS NOT NULL
                          AND band <> 'DECAYED'
                    ) AS second_degree
                """,
                (rs, rowNum) -> new PairState(
                        rs.getBoolean("existing_pair"),
                        rs.getInt("first_degree"),
                        rs.getInt("second_degree")
                ),
                pair.first().accessLevel(),
                graphVersion,
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.first().accessLevel(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                graphVersion,
                pair.second().accessLevel(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                graphVersion
        );
    }

    /**
     * Writes both directions atomically in one statement. The lease predicate is
     * part of the mutation itself, so fencing is checked at write time rather
     * than by a racy preliminary probe.
     */
    private void upsertPair(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            double semanticSimilarity,
            Instant observedAt
    ) {
        Timestamp observed = Timestamp.from(observedAt);
        int changed = jdbcTemplate.update(
                """
                INSERT INTO knowledge_chunk_association (
                    access_level,
                    source_document_id,
                    source_generation,
                    source_chunk_id,
                    target_document_id,
                    target_generation,
                    target_chunk_id,
                    band,
                    weight,
                    semantic_similarity,
                    semantic_seeded_at,
                    semantic_last_seen_at,
                    support_count,
                    context_count,
                    citation_count,
                    query_support_sketch,
                    distinct_query_support,
                    graph_version,
                    first_seen_at,
                    last_seen_at,
                    last_reinforced_at,
                    updated_at
                )
                SELECT
                    candidate.access_level,
                    candidate.source_document_id,
                    candidate.source_generation,
                    candidate.source_chunk_id,
                    candidate.target_document_id,
                    candidate.target_generation,
                    candidate.target_chunk_id,
                    'CANDIDATE',
                    0,
                    ?,
                    ?,
                    ?,
                    0,
                    0,
                    0,
                    0::bit(256),
                    0,
                    ?,
                    ?,
                    ?,
                    ?,
                    clock_timestamp()
                FROM (
                    VALUES
                        (?, ?, ?, ?, ?, ?, ?),
                        (?, ?, ?, ?, ?, ?, ?)
                ) AS candidate(
                    access_level,
                    source_document_id,
                    source_generation,
                    source_chunk_id,
                    target_document_id,
                    target_generation,
                    target_chunk_id
                )
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
                    source_document_id,
                    source_generation,
                    source_chunk_id,
                    target_document_id,
                    target_generation,
                    target_chunk_id,
                    graph_version
                ) DO UPDATE SET
                    semantic_similarity = EXCLUDED.semantic_similarity,
                    semantic_seeded_at = coalesce(
                        knowledge_chunk_association.semantic_seeded_at,
                        EXCLUDED.semantic_seeded_at
                    ),
                    semantic_last_seen_at = EXCLUDED.semantic_last_seen_at,
                    band = CASE
                        WHEN knowledge_chunk_association.band = 'DECAYED'
                             AND knowledge_chunk_association.support_count = 0
                             AND knowledge_chunk_association.context_count = 0
                             AND knowledge_chunk_association.citation_count = 0
                             AND knowledge_chunk_association.distinct_query_support = 0
                        THEN 'CANDIDATE'
                        ELSE knowledge_chunk_association.band
                    END,
                    compaction_required = TRUE,
                    updated_at = clock_timestamp()
                """,
                semanticSimilarity,
                observed,
                observed,
                authority.graphVersion(),
                observed,
                observed,
                observed,
                pair.first().accessLevel(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.second().accessLevel(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (changed != 2) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream semantic prior pair write rejected by fencing"
            );
        }
    }

    /**
     * Clears only the active semantic prior. Historical semantic timestamps and
     * all learned counters/bands remain untouched. The live lease predicate is
     * part of the final DML, and a one-sided pair is treated as corruption so
     * the surrounding transaction rolls back the candidate state transition.
     */
    private int retirePriorInTransaction(
            DreamLeaseManager.Authority authority,
            DreamPair pair
    ) {
        RetirementWriteResult result = jdbcTemplate.queryForObject(
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
                    UPDATE knowledge_chunk_association edge
                    SET semantic_similarity = NULL,
                        compaction_required = TRUE,
                        updated_at = clock_timestamp()
                    WHERE edge.access_level = ?
                      AND edge.graph_version = ?
                      AND edge.semantic_similarity IS NOT NULL
                      AND (
                          (
                              edge.source_document_id = ?
                              AND edge.source_generation = ?
                              AND edge.source_chunk_id = ?
                              AND edge.target_document_id = ?
                              AND edge.target_generation = ?
                              AND edge.target_chunk_id = ?
                          ) OR (
                              edge.source_document_id = ?
                              AND edge.source_generation = ?
                              AND edge.source_chunk_id = ?
                              AND edge.target_document_id = ?
                              AND edge.target_generation = ?
                              AND edge.target_chunk_id = ?
                          )
                      )
                      AND EXISTS (SELECT 1 FROM live_authority)
                    RETURNING 1
                )
                SELECT EXISTS(SELECT 1 FROM live_authority) AS owned,
                       count(updated.*) AS changed
                FROM updated
                """,
                (rs, rowNum) -> new RetirementWriteResult(
                        rs.getBoolean("owned"),
                        rs.getInt("changed")
                ),
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken(),
                pair.first().accessLevel(),
                authority.graphVersion(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId(),
                pair.first().documentId(),
                pair.first().generation(),
                pair.first().chunkId()
        );
        if (result == null || !result.owned()) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream semantic prior retirement rejected by fencing"
            );
        }
        if (result.changed() != 0 && result.changed() != 2) {
            throw new IllegalStateException(
                    "Dream semantic prior symmetric retirement invariant violated"
            );
        }
        return result.changed();
    }

    private record PairState(
            boolean existingPair,
            int firstDegree,
            int secondDegree
    ) {
    }

    private record RetirementWriteResult(boolean owned, int changed) {
    }

    public enum ApplyResult {
        APPLIED,
        REFRESHED,
        DEGREE_LIMIT,
        APPLY_DISABLED,
        REJECTED
    }

    public enum RetirementResult {
        NO_CHANGE(0),
        CANDIDATE_ONLY(1),
        CANDIDATE_AND_PRIOR(3);

        private final int durableRows;

        RetirementResult(int durableRows) {
            this.durableRows = durableRows;
        }

        int durableRows() {
            return durableRows;
        }
    }
}
