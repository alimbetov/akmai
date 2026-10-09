package kz.alimbetov.akmai.knowledge.graph;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdaptiveGraphMaintenanceService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AdaptiveGraphProperties properties;
    private final AdaptiveGraphScoreCalculator calculator;
    private final GraphMutationLocks graphMutationLocks;

    public AdaptiveGraphMaintenanceService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AdaptiveGraphProperties properties,
            AdaptiveGraphScoreCalculator calculator
    ) {
        this(
                jdbcTemplate,
                transactionTemplate,
                properties,
                calculator,
                new GraphMutationLocks(jdbcTemplate)
        );
    }

    @Autowired
    public AdaptiveGraphMaintenanceService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AdaptiveGraphProperties properties,
            AdaptiveGraphScoreCalculator calculator,
            GraphMutationLocks graphMutationLocks
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.calculator = calculator;
        this.graphMutationLocks = graphMutationLocks;
    }

    public MaintenanceBatch maintainBatch() {
        int batchSize = properties.maintenance().batchSize();
        Instant now = Instant.now();
        Instant scoreCutoff = now.minus(
                properties.scoring().rescoreInterval()
        );

        List<MaintenanceEdge> due = inTransaction(() ->
                claimScoreBatch(scoreCutoff, batchSize)
        );
        EnumMap<Transition, Integer> transitions =
                new EnumMap<>(Transition.class);
        TreeSet<ChunkGraphNode> touchedSources = new TreeSet<>();
        int scored = 0;

        for (MaintenanceEdge candidate : due) {
            ScoreOutcome outcome = transactionTemplate.execute(status ->
                    scoreCandidate(candidate, scoreCutoff, now)
            );
            if (outcome == null) {
                continue;
            }
            scored++;
            touchedSources.add(outcome.source());
            touchedSources.add(outcome.target());
            if (outcome.from() != outcome.to()) {
                transitions.merge(
                        Transition.of(outcome.from(), outcome.to()),
                        1,
                        Integer::sum
                );
            }
        }

        Instant compactionCutoff = Instant.now();
        Set<ChunkGraphNode> claimedSources = inTransaction(() ->
                claimCompactionSources(
                        Math.max(batchSize, batchSize * 4)
                )
        );
        touchedSources.addAll(claimedSources);

        int evicted = compactSources(
                touchedSources,
                compactionCutoff,
                batchSize
        );
        int purged = purgeDecayed(
                now.minus(properties.scoring().decayedTtl()),
                batchSize
        );

        boolean saturated = due.size() >= batchSize
                || evicted >= batchSize
                || purged >= batchSize;

        return new MaintenanceBatch(
                scored,
                evicted,
                purged,
                Map.copyOf(transitions),
                saturated
        );
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    private ScoreOutcome scoreCandidate(
            MaintenanceEdge candidate,
            Instant cutoff,
            Instant now
    ) {
        if (!graphMutationLocks.tryLockEligiblePublishedNodes(
                List.of(candidate.source(), candidate.target())
        )) {
            return null;
        }

        MaintenanceEdge current = lockScoreCandidate(
                candidate.source(),
                candidate.target(),
                cutoff
        );
        if (current == null) {
            return null;
        }

        AdaptiveGraphScoreCalculator.ScoreDecision decision =
                calculator.evaluate(
                        current.band(),
                        current.distinctQuerySupport(),
                        current.contextCount(),
                        current.citationCount(),
                        current.lastReinforcedAt(),
                        now
                );
        updatePair(current, decision, now);
        return new ScoreOutcome(
                current.source(),
                current.target(),
                current.band(),
                decision.targetBand()
        );
    }

    private List<MaintenanceEdge> claimScoreBatch(
            Instant cutoff,
            int limit
    ) {
        return jdbcTemplate.query(
                """
                SELECT access_level,
                       source_document_id,
                       source_generation,
                       source_chunk_id,
                       target_document_id,
                       target_generation,
                       target_chunk_id,
                       band,
                       distinct_query_support,
                       context_count,
                       citation_count,
                       last_reinforced_at
                FROM knowledge_chunk_association
                WHERE band IN ('CANDIDATE', 'WARM', 'HOT')
                  AND graph_version = ?
                  AND (
                      last_scored_at IS NULL
                      OR last_scored_at <= ?
                  )
                  AND ROW(
                      source_document_id,
                      source_generation,
                      source_chunk_id
                  ) < ROW(
                      target_document_id,
                      target_generation,
                      target_chunk_id
                  )
                ORDER BY last_scored_at NULLS FIRST,
                         last_reinforced_at,
                         access_level,
                         source_document_id,
                         source_generation,
                         source_chunk_id,
                         target_document_id,
                         target_generation,
                         target_chunk_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                ps -> {
                    ps.setInt(1, properties.graphVersion());
                    ps.setTimestamp(2, Timestamp.from(cutoff));
                    ps.setInt(3, limit);
                },
                (rs, rowNum) -> mapMaintenanceEdge(rs)
        );
    }

    private MaintenanceEdge lockScoreCandidate(
            ChunkGraphNode source,
            ChunkGraphNode target,
            Instant cutoff
    ) {
        return jdbcTemplate.query(
                """
                SELECT access_level,
                       source_document_id,
                       source_generation,
                       source_chunk_id,
                       target_document_id,
                       target_generation,
                       target_chunk_id,
                       band,
                       distinct_query_support,
                       context_count,
                       citation_count,
                       last_reinforced_at
                FROM knowledge_chunk_association
                WHERE access_level = ?
                  AND graph_version = ?
                  AND source_document_id = ?
                  AND source_generation = ?
                  AND source_chunk_id = ?
                  AND target_document_id = ?
                  AND target_generation = ?
                  AND target_chunk_id = ?
                  AND band IN ('CANDIDATE', 'WARM', 'HOT')
                  AND (
                      last_scored_at IS NULL
                      OR last_scored_at <= ?
                  )
                FOR UPDATE
                """,
                (rs, rowNum) -> mapMaintenanceEdge(rs),
                source.accessLevel(),
                properties.graphVersion(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                target.documentId(),
                target.generation(),
                target.chunkId(),
                Timestamp.from(cutoff)
        ).stream().findFirst().orElse(null);
    }

    private MaintenanceEdge mapMaintenanceEdge(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        long accessLevel = rs.getLong("access_level");
        return new MaintenanceEdge(
                new ChunkGraphNode(
                        accessLevel,
                        rs.getString("source_document_id"),
                        rs.getLong("source_generation"),
                        rs.getString("source_chunk_id")
                ),
                new ChunkGraphNode(
                        accessLevel,
                        rs.getString("target_document_id"),
                        rs.getLong("target_generation"),
                        rs.getString("target_chunk_id")
                ),
                AssociationBand.valueOf(rs.getString("band")),
                rs.getLong("distinct_query_support"),
                rs.getLong("context_count"),
                rs.getLong("citation_count"),
                rs.getTimestamp("last_reinforced_at").toInstant()
        );
    }

    private void updatePair(
            MaintenanceEdge edge,
            AdaptiveGraphScoreCalculator.ScoreDecision decision,
            Instant now
    ) {
        boolean decayed = decision.targetBand() == AssociationBand.DECAYED;
        int updated = jdbcTemplate.update(
                """
                UPDATE knowledge_chunk_association
                SET weight = ?,
                    band = ?,
                    last_scored_at = ?,
                    band_changed_at = CASE
                        WHEN band <> ? THEN ?
                        ELSE band_changed_at
                    END,
                    decayed_at = CASE
                        WHEN ? = 'DECAYED'
                            THEN COALESCE(decayed_at, ?)
                        ELSE NULL
                    END,
                    compaction_required = ?,
                    updated_at = clock_timestamp()
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
                """,
                decision.effectiveWeight(),
                decision.targetBand().name(),
                Timestamp.from(now),
                decision.targetBand().name(),
                Timestamp.from(now),
                decision.targetBand().name(),
                decayed ? Timestamp.from(now) : null,
                !decayed,
                edge.source().accessLevel(),
                properties.graphVersion(),
                edge.source().documentId(),
                edge.source().generation(),
                edge.source().chunkId(),
                edge.target().documentId(),
                edge.target().generation(),
                edge.target().chunkId(),
                edge.target().documentId(),
                edge.target().generation(),
                edge.target().chunkId(),
                edge.source().documentId(),
                edge.source().generation(),
                edge.source().chunkId()
        );
        if (updated != 2) {
            throw new IllegalStateException(
                    "Adaptive graph symmetric pair invariant violated"
            );
        }
    }

    private Set<ChunkGraphNode> claimCompactionSources(int limit) {
        List<ChunkGraphNode> claimed = jdbcTemplate.query(
                """
                SELECT access_level,
                       source_document_id,
                       source_generation,
                       source_chunk_id
                FROM knowledge_chunk_association
                WHERE compaction_required
                  AND graph_version = ?
                  AND band IN ('CANDIDATE', 'WARM', 'HOT')
                ORDER BY updated_at,
                         access_level,
                         source_document_id,
                         source_generation,
                         source_chunk_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkGraphNode(
                        rs.getLong("access_level"),
                        rs.getString("source_document_id"),
                        rs.getLong("source_generation"),
                        rs.getString("source_chunk_id")
                ),
                properties.graphVersion(),
                limit
        );
        return new LinkedHashSet<>(claimed);
    }

    private int compactSources(
            Set<ChunkGraphNode> sources,
            Instant cutoff,
            int maxEvictions
    ) {
        int evicted = 0;
        for (ChunkGraphNode source : sources) {
            if (evicted >= maxEvictions) {
                break;
            }
            int remaining = maxEvictions - evicted;
            List<ChunkGraphNode> overflow = inTransaction(() ->
                    overflowTargets(source, remaining)
            );
            for (ChunkGraphNode target : overflow) {
                Integer deleted = transactionTemplate.execute(status ->
                        evictOverflowPair(source, target)
                );
                if (deleted != null && deleted > 0) {
                    evicted++;
                    if (evicted >= maxEvictions) {
                        break;
                    }
                }
            }

            transactionTemplate.executeWithoutResult(status ->
                    clearCompactionFlagIfComplete(source, cutoff)
            );
        }
        return evicted;
    }

    private int evictOverflowPair(
            ChunkGraphNode source,
            ChunkGraphNode target
    ) {
        if (!graphMutationLocks.tryLockEligiblePublishedNodes(
                List.of(source, target)
        )) {
            return 0;
        }
        if (!isOverflowTarget(source, target)) {
            return 0;
        }
        return deletePair(source, target);
    }

    private void clearCompactionFlagIfComplete(
            ChunkGraphNode source,
            Instant cutoff
    ) {
        if (!graphMutationLocks.tryLockEligiblePublishedNodes(List.of(source))) {
            return;
        }
        if (hasOverflow(source)) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE knowledge_chunk_association
                SET compaction_required = FALSE
                WHERE access_level = ?
                  AND graph_version = ?
                  AND source_document_id = ?
                  AND source_generation = ?
                  AND source_chunk_id = ?
                  AND updated_at <= ?
                """,
                source.accessLevel(),
                properties.graphVersion(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                Timestamp.from(cutoff)
        );
    }

    private List<ChunkGraphNode> overflowTargets(
            ChunkGraphNode source,
            int limit
    ) {
        AdaptiveGraphProperties.BandQuotas quotas = properties.quotas();
        return jdbcTemplate.query(
                """
                WITH ranked AS (
                    SELECT tableoid,
                           ctid,
                           target_document_id,
                           target_generation,
                           target_chunk_id,
                           band,
                           row_number() OVER (
                               PARTITION BY band
                               ORDER BY weight DESC,
                                        distinct_query_support DESC,
                                        citation_count DESC,
                                        last_reinforced_at DESC,
                                        target_document_id,
                                        target_generation,
                                        target_chunk_id
                           ) AS position
                    FROM knowledge_chunk_association
                    WHERE access_level = ?
                      AND graph_version = ?
                      AND source_document_id = ?
                      AND source_generation = ?
                      AND source_chunk_id = ?
                      AND band IN ('CANDIDATE', 'WARM', 'HOT')
                ),
                overflow AS (
                    SELECT *
                    FROM ranked
                    WHERE (band = 'HOT' AND position > ?)
                       OR (band = 'WARM' AND position > ?)
                       OR (band = 'CANDIDATE' AND position > ?)
                )
                SELECT edge.target_document_id,
                       edge.target_generation,
                       edge.target_chunk_id
                FROM knowledge_chunk_association edge
                JOIN overflow
                  ON edge.tableoid = overflow.tableoid
                 AND edge.ctid = overflow.ctid
                ORDER BY CASE overflow.band
                             WHEN 'CANDIDATE' THEN 0
                             WHEN 'WARM' THEN 1
                             WHEN 'HOT' THEN 2
                             ELSE 3
                         END,
                         overflow.position DESC,
                         edge.target_document_id,
                         edge.target_generation,
                         edge.target_chunk_id
                FOR UPDATE OF edge SKIP LOCKED
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkGraphNode(
                        source.accessLevel(),
                        rs.getString("target_document_id"),
                        rs.getLong("target_generation"),
                        rs.getString("target_chunk_id")
                ),
                source.accessLevel(),
                properties.graphVersion(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                quotas.hot(),
                quotas.warm(),
                quotas.candidate(),
                limit
        );
    }

    private boolean isOverflowTarget(
            ChunkGraphNode source,
            ChunkGraphNode target
    ) {
        AdaptiveGraphProperties.BandQuotas quotas = properties.quotas();
        Boolean overflow = jdbcTemplate.queryForObject(
                """
                WITH ranked AS (
                    SELECT target_document_id,
                           target_generation,
                           target_chunk_id,
                           band,
                           row_number() OVER (
                               PARTITION BY band
                               ORDER BY weight DESC,
                                        distinct_query_support DESC,
                                        citation_count DESC,
                                        last_reinforced_at DESC,
                                        target_document_id,
                                        target_generation,
                                        target_chunk_id
                           ) AS position
                    FROM knowledge_chunk_association
                    WHERE access_level = ?
                      AND graph_version = ?
                      AND source_document_id = ?
                      AND source_generation = ?
                      AND source_chunk_id = ?
                      AND band IN ('CANDIDATE', 'WARM', 'HOT')
                )
                SELECT EXISTS (
                    SELECT 1
                    FROM ranked
                    WHERE target_document_id = ?
                      AND target_generation = ?
                      AND target_chunk_id = ?
                      AND (
                          (band = 'HOT' AND position > ?)
                          OR (band = 'WARM' AND position > ?)
                          OR (band = 'CANDIDATE' AND position > ?)
                      )
                )
                """,
                Boolean.class,
                source.accessLevel(),
                properties.graphVersion(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                target.documentId(),
                target.generation(),
                target.chunkId(),
                quotas.hot(),
                quotas.warm(),
                quotas.candidate()
        );
        return Boolean.TRUE.equals(overflow);
    }

    private boolean hasOverflow(ChunkGraphNode source) {
        AdaptiveGraphProperties.BandQuotas quotas = properties.quotas();
        Boolean overflow = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM (
                        SELECT band,
                               count(*) AS edge_count
                        FROM knowledge_chunk_association
                        WHERE access_level = ?
                          AND graph_version = ?
                          AND source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND band IN ('CANDIDATE', 'WARM', 'HOT')
                        GROUP BY band
                    ) counts
                    WHERE (band = 'HOT' AND edge_count > ?)
                       OR (band = 'WARM' AND edge_count > ?)
                       OR (band = 'CANDIDATE' AND edge_count > ?)
                )
                """,
                Boolean.class,
                source.accessLevel(),
                properties.graphVersion(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                quotas.hot(),
                quotas.warm(),
                quotas.candidate()
        );
        return Boolean.TRUE.equals(overflow);
    }

    private int purgeDecayed(Instant cutoff, int limit) {
        List<LogicalPair> expired = inTransaction(() ->
                claimDecayedPairs(cutoff, limit)
        );
        int purged = 0;
        for (LogicalPair pair : expired) {
            Integer deleted = transactionTemplate.execute(status ->
                    purgeDecayedPair(pair, cutoff)
            );
            if (deleted != null && deleted > 0) {
                purged++;
            }
        }
        return purged;
    }

    private List<LogicalPair> claimDecayedPairs(
            Instant cutoff,
            int limit
    ) {
        return jdbcTemplate.query(
                """
                SELECT access_level,
                       source_document_id,
                       source_generation,
                       source_chunk_id,
                       target_document_id,
                       target_generation,
                       target_chunk_id
                FROM knowledge_chunk_association
                WHERE band = 'DECAYED'
                  AND graph_version = ?
                  AND decayed_at <= ?
                  AND ROW(
                      source_document_id,
                      source_generation,
                      source_chunk_id
                  ) < ROW(
                      target_document_id,
                      target_generation,
                      target_chunk_id
                  )
                ORDER BY decayed_at,
                         access_level,
                         source_document_id,
                         source_generation,
                         source_chunk_id,
                         target_document_id,
                         target_generation,
                         target_chunk_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                ps -> {
                    ps.setInt(1, properties.graphVersion());
                    ps.setTimestamp(2, Timestamp.from(cutoff));
                    ps.setInt(3, limit);
                },
                (rs, rowNum) -> {
                    long accessLevel = rs.getLong("access_level");
                    return new LogicalPair(
                            new ChunkGraphNode(
                                    accessLevel,
                                    rs.getString("source_document_id"),
                                    rs.getLong("source_generation"),
                                    rs.getString("source_chunk_id")
                            ),
                            new ChunkGraphNode(
                                    accessLevel,
                                    rs.getString("target_document_id"),
                                    rs.getLong("target_generation"),
                                    rs.getString("target_chunk_id")
                            )
                    );
                }
        );
    }

    private int purgeDecayedPair(
            LogicalPair pair,
            Instant cutoff
    ) {
        if (!graphMutationLocks.tryLockRetirementNodes(
                List.of(pair.left(), pair.right())
        )) {
            return 0;
        }
        Integer eligibleRows = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE access_level = ?
                  AND graph_version = ?
                  AND band = 'DECAYED'
                  AND decayed_at <= ?
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
                """,
                Integer.class,
                pair.left().accessLevel(),
                properties.graphVersion(),
                Timestamp.from(cutoff),
                pair.left().documentId(),
                pair.left().generation(),
                pair.left().chunkId(),
                pair.right().documentId(),
                pair.right().generation(),
                pair.right().chunkId(),
                pair.right().documentId(),
                pair.right().generation(),
                pair.right().chunkId(),
                pair.left().documentId(),
                pair.left().generation(),
                pair.left().chunkId()
        );
        if (eligibleRows == null || eligibleRows == 0) {
            return 0;
        }
        if (eligibleRows != 2) {
            throw new IllegalStateException(
                    "Adaptive graph symmetric decayed pair invariant violated"
            );
        }
        return deletePair(pair.left(), pair.right());
    }

    private int deletePair(
            ChunkGraphNode left,
            ChunkGraphNode right
    ) {
        int deleted = jdbcTemplate.update(
                """
                DELETE FROM knowledge_chunk_association
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
                """,
                left.accessLevel(),
                properties.graphVersion(),
                left.documentId(),
                left.generation(),
                left.chunkId(),
                right.documentId(),
                right.generation(),
                right.chunkId(),
                right.documentId(),
                right.generation(),
                right.chunkId(),
                left.documentId(),
                left.generation(),
                left.chunkId()
        );
        if (deleted != 0 && deleted != 2) {
            throw new IllegalStateException(
                    "Adaptive graph symmetric delete invariant violated"
            );
        }
        return deleted;
    }

    private record MaintenanceEdge(
            ChunkGraphNode source,
            ChunkGraphNode target,
            AssociationBand band,
            long distinctQuerySupport,
            long contextCount,
            long citationCount,
            Instant lastReinforcedAt
    ) {
    }

    private record ScoreOutcome(
            ChunkGraphNode source,
            ChunkGraphNode target,
            AssociationBand from,
            AssociationBand to
    ) {
    }

    private record LogicalPair(
            ChunkGraphNode left,
            ChunkGraphNode right
    ) {
    }

    public enum Transition {
        CANDIDATE_TO_WARM,
        CANDIDATE_TO_DECAYED,
        WARM_TO_HOT,
        WARM_TO_CANDIDATE,
        HOT_TO_WARM;

        static Transition of(
                AssociationBand from,
                AssociationBand to
        ) {
            return Transition.valueOf(
                    from.name() + "_TO_" + to.name()
            );
        }

        public AssociationBand from() {
            return AssociationBand.valueOf(
                    name().substring(0, name().indexOf("_TO_"))
            );
        }

        public AssociationBand to() {
            return AssociationBand.valueOf(
                    name().substring(name().indexOf("_TO_") + 4)
            );
        }
    }

    public record MaintenanceBatch(
            int scored,
            int evicted,
            int purged,
            Map<Transition, Integer> transitions,
            boolean saturated
    ) {
        public MaintenanceBatch {
            transitions = transitions == null
                    ? Map.of()
                    : Map.copyOf(transitions);
        }

        static MaintenanceBatch empty() {
            return new MaintenanceBatch(
                    0,
                    0,
                    0,
                    Map.of(),
                    false
            );
        }

        public int work() {
            return scored + evicted + purged;
        }
    }
}
