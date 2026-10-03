package kz.alimbetov.akmai.knowledge.graph;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdaptiveGraphMaintenanceService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AdaptiveGraphProperties properties;
    private final AdaptiveGraphScoreCalculator calculator;

    public AdaptiveGraphMaintenanceService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AdaptiveGraphProperties properties,
            AdaptiveGraphScoreCalculator calculator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.calculator = calculator;
    }

    public MaintenanceBatch maintainBatch() {
        MaintenanceBatch result = transactionTemplate.execute(status ->
                maintainBatchTransactional()
        );
        return result == null ? MaintenanceBatch.empty() : result;
    }

    private MaintenanceBatch maintainBatchTransactional() {
        int batchSize = properties.maintenance().batchSize();
        Instant now = Instant.now();
        Instant scoreCutoff = now.minus(
                properties.scoring().rescoreInterval()
        );

        List<MaintenanceEdge> due = claimScoreBatch(
                scoreCutoff,
                batchSize
        );
        EnumMap<Transition, Integer> transitions =
                new EnumMap<>(Transition.class);
        TreeSet<ChunkGraphNode> touchedSources = new TreeSet<>();

        for (MaintenanceEdge edge : due) {
            AdaptiveGraphScoreCalculator.ScoreDecision decision =
                    calculator.evaluate(
                            edge.band(),
                            edge.distinctQuerySupport(),
                            edge.contextCount(),
                            edge.citationCount(),
                            edge.lastReinforcedAt(),
                            now
                    );
            updatePair(edge, decision, now);
            touchedSources.add(edge.source());
            touchedSources.add(edge.target());
            if (edge.band() != decision.targetBand()) {
                Transition transition = Transition.of(
                        edge.band(),
                        decision.targetBand()
                );
                transitions.merge(transition, 1, Integer::sum);
            }
        }

        Instant compactionCutoff = Instant.now();
        touchedSources.addAll(
                claimCompactionSources(
                        Math.max(batchSize, batchSize * 4)
                )
        );

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
                due.size(),
                evicted,
                purged,
                Map.copyOf(transitions),
                saturated
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
                    ps.setTimestamp(1, Timestamp.from(cutoff));
                    ps.setInt(2, limit);
                },
                (rs, rowNum) -> {
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
                    compaction_required = TRUE,
                    updated_at = clock_timestamp()
                WHERE access_level = ?
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
                edge.source().accessLevel(),
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
            List<ChunkGraphNode> overflow = overflowTargets(
                    source,
                    remaining
            );
            for (ChunkGraphNode target : overflow) {
                int deleted = deletePair(source, target);
                if (deleted > 0) {
                    evicted++;
                }
            }

            if (!hasOverflow(source)) {
                jdbcTemplate.update(
                        """
                        UPDATE knowledge_chunk_association
                        SET compaction_required = FALSE
                        WHERE access_level = ?
                          AND source_document_id = ?
                          AND source_generation = ?
                          AND source_chunk_id = ?
                          AND updated_at <= ?
                        """,
                        source.accessLevel(),
                        source.documentId(),
                        source.generation(),
                        source.chunkId(),
                        Timestamp.from(cutoff)
                );
            }
        }
        return evicted;
    }

    private List<ChunkGraphNode> overflowTargets(
            ChunkGraphNode source,
            int limit
    ) {
        AdaptiveGraphProperties.BandQuotas quotas = properties.quotas();
        return jdbcTemplate.query(
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
                      AND source_document_id = ?
                      AND source_generation = ?
                      AND source_chunk_id = ?
                      AND band IN ('CANDIDATE', 'WARM', 'HOT')
                )
                SELECT target_document_id,
                       target_generation,
                       target_chunk_id
                FROM ranked
                WHERE (band = 'HOT' AND position > ?)
                   OR (band = 'WARM' AND position > ?)
                   OR (band = 'CANDIDATE' AND position > ?)
                ORDER BY band,
                         position DESC,
                         target_document_id,
                         target_generation,
                         target_chunk_id
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkGraphNode(
                        source.accessLevel(),
                        rs.getString("target_document_id"),
                        rs.getLong("target_generation"),
                        rs.getString("target_chunk_id")
                ),
                source.accessLevel(),
                source.documentId(),
                source.generation(),
                source.chunkId(),
                quotas.hot(),
                quotas.warm(),
                quotas.candidate(),
                limit
        );
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
        List<LogicalPair> expired = jdbcTemplate.query(
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
                    ps.setTimestamp(1, Timestamp.from(cutoff));
                    ps.setInt(2, limit);
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

        int purged = 0;
        for (LogicalPair pair : expired) {
            if (deletePair(pair.left(), pair.right()) > 0) {
                purged++;
            }
        }
        return purged;
    }

    private int deletePair(
            ChunkGraphNode left,
            ChunkGraphNode right
    ) {
        int deleted = jdbcTemplate.update(
                """
                DELETE FROM knowledge_chunk_association
                WHERE access_level = ?
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
