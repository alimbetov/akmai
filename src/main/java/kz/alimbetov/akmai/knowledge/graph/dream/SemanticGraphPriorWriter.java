package kz.alimbetov.akmai.knowledge.graph.dream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.GraphLifecycleGuard;
import kz.alimbetov.akmai.knowledge.graph.GraphNodeLockManager;
import kz.alimbetov.akmai.knowledge.graph.SemanticGraphStateRepository;
import kz.alimbetov.akmai.util.TransactionTimeouts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restricted DREAM-5 mutation boundary. It may materialize a semantic prior
 * only; learned evidence counters remain authoritative to online learning.
 */
@Component
public class SemanticGraphPriorWriter {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final DreamRuntimeSwitches switches;
    private final AdaptiveGraphProperties graphProperties;
    private final SemanticMemoryProperties semanticMemoryProperties;
    private final GraphNodeLockManager graphNodeLockManager;
    private final GraphLifecycleGuard lifecycleGuard;
    private final SemanticGraphStateRepository graphStateRepository;
    private final DreamAuthorityGuard authorityGuard;

    public SemanticGraphPriorWriter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            DreamRuntimeSwitches switches,
            AdaptiveGraphProperties graphProperties,
            SemanticMemoryProperties semanticMemoryProperties,
            GraphNodeLockManager graphNodeLockManager,
            GraphLifecycleGuard lifecycleGuard,
            SemanticGraphStateRepository graphStateRepository,
            DreamAuthorityGuard authorityGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.switches = switches;
        this.graphProperties = graphProperties;
        this.semanticMemoryProperties = semanticMemoryProperties;
        this.graphNodeLockManager = graphNodeLockManager;
        this.lifecycleGuard = lifecycleGuard;
        this.graphStateRepository = graphStateRepository;
        this.authorityGuard = authorityGuard;
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

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(TransactionTimeouts.seconds(
                graphProperties.dream().transactionTimeout()
        ));
        ApplyResult result = transaction.execute(status -> applyInTransaction(
                authority,
                pair,
                semanticSimilarity,
                observedAt
        ));
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
        lifecycleGuard.lockSemanticEligibleCanonical(nodes);
        graphNodeLockManager.lockCanonical(nodes);

        SemanticGraphStateRepository.PairState state = graphStateRepository.inspect(
                pair.first(),
                pair.second(),
                authority.graphVersion()
        );
        int maxSemanticDegree = semanticMemoryProperties.getMaxEdgesPerChunk();
        if (!state.degreeAvailable(maxSemanticDegree)) {
            return ApplyResult.DEGREE_LIMIT;
        }

        upsertDirection(
                authority,
                pair.first(),
                pair.second(),
                semanticSimilarity,
                observedAt
        );
        upsertDirection(
                authority,
                pair.second(),
                pair.first(),
                semanticSimilarity,
                observedAt
        );
        return state.pairExists() ? ApplyResult.REFRESHED : ApplyResult.APPLIED;
    }

    private void upsertDirection(
            DreamLeaseManager.Authority authority,
            ChunkGraphNode source,
            ChunkGraphNode target,
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
                    ?, ?, ?, ?, ?, ?, ?,
                    'CANDIDATE', 0, ?, ?, ?,
                    0, 0, 0, 0::bit(256), 0, ?, ?, ?, ?, clock_timestamp()
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
                source.accessLevel(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                target.documentId(),
                target.generation(),
                target.chunkId(),
                semanticSimilarity,
                observed,
                observed,
                authority.graphVersion(),
                observed,
                observed,
                observed,
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (changed != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream semantic prior write rejected by fencing"
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
