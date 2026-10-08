package kz.alimbetov.akmai.knowledge.graph.dream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.GraphLifecycleGuard;
import kz.alimbetov.akmai.knowledge.graph.GraphNodeLockManager;
import kz.alimbetov.akmai.knowledge.graph.GraphTransactionExecutor;
import kz.alimbetov.akmai.knowledge.graph.SemanticPairAdmissionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Restricted DREAM-5 mutation boundary. It may materialize a semantic prior
 * only; learned evidence counters remain authoritative to online learning.
 */
@Component
public class SemanticGraphPriorWriter {

    private final JdbcTemplate jdbcTemplate;
    private final DreamRuntimeSwitches switches;
    private final AdaptiveGraphProperties graphProperties;
    private final SemanticMemoryProperties semanticMemoryProperties;
    private final GraphNodeLockManager graphNodeLockManager;
    private final GraphLifecycleGuard graphLifecycleGuard;
    private final GraphTransactionExecutor transactionExecutor;
    private final DreamAuthorityGuard authorityGuard;
    private final SemanticPairAdmissionRepository pairAdmissionRepository;

    public SemanticGraphPriorWriter(
            JdbcTemplate jdbcTemplate,
            DreamRuntimeSwitches switches,
            AdaptiveGraphProperties graphProperties,
            SemanticMemoryProperties semanticMemoryProperties,
            GraphNodeLockManager graphNodeLockManager,
            GraphLifecycleGuard graphLifecycleGuard,
            GraphTransactionExecutor transactionExecutor,
            DreamAuthorityGuard authorityGuard,
            SemanticPairAdmissionRepository pairAdmissionRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.switches = switches;
        this.graphProperties = graphProperties;
        this.semanticMemoryProperties = semanticMemoryProperties;
        this.graphNodeLockManager = graphNodeLockManager;
        this.graphLifecycleGuard = graphLifecycleGuard;
        this.transactionExecutor = transactionExecutor;
        this.authorityGuard = authorityGuard;
        this.pairAdmissionRepository = pairAdmissionRepository;
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
        if (authority.graphVersion() != graphProperties.graphVersion()) {
            throw new IllegalArgumentException(
                    "Dream apply graph version does not match configured graph version"
            );
        }

        ApplyResult result = transactionExecutor.execute(
                graphProperties.dream().transactionTimeout(),
                () -> applyInTransaction(
                        authority,
                        pair,
                        semanticSimilarity,
                        observedAt
                )
        );
        return result == null ? ApplyResult.REJECTED : result;
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
        authorityGuard.requireOwned(authority);

        List<ChunkGraphNode> nodes = List.of(pair.first(), pair.second());
        nodes.stream().sorted().forEach(graphLifecycleGuard::lockPublishedReadyActive);
        graphNodeLockManager.lockCanonical(nodes);

        SemanticPairAdmissionRepository.PairAdmissionStats stats =
                pairAdmissionRepository.load(
                        pair.first(),
                        pair.second(),
                        authority.graphVersion()
                );
        int maxSemanticDegree = semanticMemoryProperties.getMaxEdgesPerChunk();
        if (!stats.existingPair()
                && (stats.firstDegree() >= maxSemanticDegree
                || stats.secondDegree() >= maxSemanticDegree)) {
            return ApplyResult.DEGREE_LIMIT;
        }

        upsertPair(
                authority,
                pair,
                semanticSimilarity,
                observedAt
        );
        return stats.existingPair() ? ApplyResult.REFRESHED : ApplyResult.APPLIED;
    }

    private void upsertPair(
            DreamLeaseManager.Authority authority,
            DreamPair pair,
            double semanticSimilarity,
            Instant observedAt
    ) {
        Timestamp observed = Timestamp.from(observedAt);
        ChunkGraphNode first = pair.first();
        ChunkGraphNode second = pair.second();
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
                    pair_row.access_level,
                    pair_row.source_document_id,
                    pair_row.source_generation,
                    pair_row.source_chunk_id,
                    pair_row.target_document_id,
                    pair_row.target_generation,
                    pair_row.target_chunk_id,
                    'CANDIDATE',
                    0,
                    pair_row.semantic_similarity,
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
                        (?, ?, ?, ?, ?, ?, ?, ?),
                        (?, ?, ?, ?, ?, ?, ?, ?)
                ) AS pair_row (
                    access_level,
                    source_document_id,
                    source_generation,
                    source_chunk_id,
                    target_document_id,
                    target_generation,
                    target_chunk_id,
                    semantic_similarity
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
                observed,
                observed,
                authority.graphVersion(),
                observed,
                observed,
                observed,
                first.accessLevel(),
                first.documentId(),
                first.generation(),
                first.chunkId(),
                second.documentId(),
                second.generation(),
                second.chunkId(),
                semanticSimilarity,
                second.accessLevel(),
                second.documentId(),
                second.generation(),
                second.chunkId(),
                first.documentId(),
                first.generation(),
                first.chunkId(),
                semanticSimilarity,
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (changed != 2) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream semantic pair write rejected by fencing"
            );
        }
    }

    public enum ApplyResult {
        APPLIED,
        REFRESHED,
        DEGREE_LIMIT,
        APPLY_DISABLED,
        REJECTED
    }
}
