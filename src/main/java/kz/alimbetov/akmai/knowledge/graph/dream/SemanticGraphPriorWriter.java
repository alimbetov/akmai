package kz.alimbetov.akmai.knowledge.graph.dream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.TreeSet;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
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
        transaction.setTimeout(transactionTimeoutSeconds());
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
        requireAuthority(authority);

        TreeSet<ChunkGraphNode> lockOrder = new TreeSet<>();
        lockOrder.add(pair.first());
        lockOrder.add(pair.second());
        lockOrder.forEach(this::lockPublishedGeneration);
        lockOrder.forEach(this::lockNode);

        boolean existingPair = associationExistsEitherDirection(
                pair,
                authority.graphVersion()
        );
        int maxSemanticDegree = semanticMemoryProperties.getMaxEdgesPerChunk();
        if (!existingPair
                && (semanticDegree(pair.first(), authority.graphVersion())
                        >= maxSemanticDegree
                || semanticDegree(pair.second(), authority.graphVersion())
                        >= maxSemanticDegree)) {
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
        return existingPair ? ApplyResult.REFRESHED : ApplyResult.APPLIED;
    }

    private void requireAuthority(DreamLeaseManager.Authority authority) {
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
                    "Dream semantic prior apply rejected by fencing"
            );
        }
    }

    private boolean associationExistsEitherDirection(
            DreamPair pair,
            int graphVersion
    ) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
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
                )
                """,
                Boolean.class,
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
                pair.first().chunkId()
        );
        return Boolean.TRUE.equals(exists);
    }

    private int semanticDegree(ChunkGraphNode node, int graphVersion) {
        Integer degree = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE access_level = ?
                  AND source_document_id = ?
                  AND source_generation = ?
                  AND source_chunk_id = ?
                  AND graph_version = ?
                  AND semantic_similarity IS NOT NULL
                  AND band <> 'DECAYED'
                """,
                Integer.class,
                node.accessLevel(),
                node.documentId(),
                node.generation(),
                node.chunkId(),
                graphVersion
        );
        return degree == null ? 0 : degree;
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

    private void lockPublishedGeneration(ChunkGraphNode node) {
        Integer published = jdbcTemplate.query(
                """
                SELECT 1
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND access_level = ?
                  AND published_generation = ?
                  AND lifecycle_status = 'READY'
                  AND retention_status = 'ACTIVE'
                  AND (
                      expires_at IS NULL
                      OR expires_at > clock_timestamp()
                  )
                FOR SHARE
                """,
                (rs, rowNum) -> rs.getInt(1),
                node.documentId(),
                node.accessLevel(),
                node.generation()
        ).stream().findFirst().orElse(null);
        if (published == null) {
            throw new IllegalStateException(
                    "Dream apply requires eligible ACTIVE/PUBLISHED generation"
            );
        }
    }

    private void lockNode(ChunkGraphNode node) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> {
                },
                "akmai:adaptive-graph:node:" + node.lockKey()
        );
    }

    private int transactionTimeoutSeconds() {
        long seconds = graphProperties.dream().transactionTimeout().toSeconds();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }

    public enum ApplyResult {
        APPLIED,
        REFRESHED,
        DEGREE_LIMIT,
        APPLY_DISABLED,
        REJECTED
    }
}
