package kz.alimbetov.akmai.knowledge.graph;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AdaptiveChunkGraphRepository
        implements AdaptiveGraphLookupReader {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AdaptiveChunkGraphRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public void reinforceSymmetric(
            ChunkGraphNode left,
            ChunkGraphNode right,
            AssociationBand band,
            AssociationEvidence evidence
    ) {
        reinforceSymmetricBatch(List.of(
                new AssociationObservation(left, right, band, evidence)
        ));
    }

    public void reinforceSymmetricBatch(
            List<AssociationObservation> observations
    ) {
        if (observations == null || observations.isEmpty()) {
            return;
        }

        TreeSet<ChunkGraphNode> lockOrder = new TreeSet<>();
        for (AssociationObservation observation : observations) {
            if (observation == null) {
                throw new IllegalArgumentException(
                        "association observation must not be null"
                );
            }
            requirePair(observation.left(), observation.right());
            requireBand(observation.band());
            if (observation.evidence() == null) {
                throw new IllegalArgumentException(
                        "evidence must not be null"
                );
            }
            lockOrder.add(observation.left());
            lockOrder.add(observation.right());
        }

        transactionTemplate.executeWithoutResult(status -> {
            lockOrder.forEach(this::lockPublishedGeneration);
            lockOrder.forEach(this::lockNode);
            observations.forEach(observation ->
                    upsertPair(
                            observation.left(),
                            observation.right(),
                            observation.band(),
                            observation.evidence()
                    )
            );
        });
    }

    public List<ChunkAssociation> findRelated(
            Set<Long> allowedAccessLevels,
            ChunkGraphNode source,
            int graphVersion,
            Set<AssociationBand> bands,
            double minimumWeight,
            int limit
    ) {
        requireAllowedAccessLevels(allowedAccessLevels);
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (!allowedAccessLevels.contains(source.accessLevel())) {
            return List.of();
        }
        if (graphVersion <= 0) {
            throw new IllegalArgumentException("graphVersion must be positive");
        }
        if (bands == null || bands.isEmpty()) {
            throw new IllegalArgumentException("bands must not be empty");
        }
        if (bands.contains(AssociationBand.DECAYED)) {
            throw new IllegalArgumentException(
                    "DECAYED edges are not eligible for online lookup"
            );
        }
        if (!Double.isFinite(minimumWeight)
                || minimumWeight < 0
                || minimumWeight > 1) {
            throw new IllegalArgumentException(
                    "minimumWeight must be in [0, 1]"
            );
        }
        if (limit <= 0 || limit > 256) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 256"
            );
        }

        List<String> bandNames = bands.stream()
                .map(Enum::name)
                .sorted()
                .toList();

        return jdbcTemplate.query(
                """
                SELECT target_document_id,
                       target_generation,
                       target_chunk_id,
                       band,
                       weight,
                       support_count,
                       context_count,
                       citation_count,
                       distinct_query_support,
                       last_reinforced_at,
                       graph_version
                FROM knowledge_chunk_association
                WHERE access_level = ?
                  AND source_document_id = ?
                  AND source_generation = ?
                  AND source_chunk_id = ?
                  AND graph_version = ?
                  AND band = ANY (?)
                  AND weight >= ?
                ORDER BY weight DESC,
                         distinct_query_support DESC,
                         citation_count DESC,
                         last_reinforced_at DESC,
                         target_document_id,
                         target_generation,
                         target_chunk_id
                LIMIT ?
                """,
                ps -> {
                    ps.setLong(1, source.accessLevel());
                    ps.setString(2, source.documentId());
                    ps.setLong(3, source.generation());
                    ps.setString(4, source.chunkId());
                    ps.setInt(5, graphVersion);
                    ps.setArray(
                            6,
                            ps.getConnection().createArrayOf(
                                    "varchar",
                                    bandNames.toArray()
                            )
                    );
                    ps.setDouble(7, minimumWeight);
                    ps.setInt(8, limit);
                },
                (rs, rowNum) -> new ChunkAssociation(
                        source,
                        new ChunkGraphNode(
                                source.accessLevel(),
                                rs.getString("target_document_id"),
                                rs.getLong("target_generation"),
                                rs.getString("target_chunk_id")
                        ),
                        AssociationBand.valueOf(rs.getString("band")),
                        rs.getDouble("weight"),
                        rs.getLong("support_count"),
                        rs.getLong("context_count"),
                        rs.getLong("citation_count"),
                        rs.getLong("distinct_query_support"),
                        rs.getTimestamp("last_reinforced_at").toInstant(),
                        rs.getInt("graph_version")
                )
        );
    }

    private void requirePair(
            ChunkGraphNode left,
            ChunkGraphNode right
    ) {
        if (left == null || right == null) {
            throw new IllegalArgumentException(
                    "association nodes must not be null"
            );
        }
        if (left.equals(right)) {
            throw new IllegalArgumentException(
                    "self association is not allowed"
            );
        }
        if (left.accessLevel() != right.accessLevel()) {
            throw new IllegalArgumentException(
                    "cross-ACL association is forbidden"
            );
        }
    }

    private void requireBand(AssociationBand band) {
        if (band == null || band == AssociationBand.DECAYED) {
            throw new IllegalArgumentException(
                    "reinforcement band must be CANDIDATE, WARM, or HOT"
            );
        }
    }

    private void requireAllowedAccessLevels(Set<Long> allowedAccessLevels) {
        if (allowedAccessLevels == null || allowedAccessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedAccessLevels must not be empty"
            );
        }
        if (allowedAccessLevels.stream()
                .anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "allowedAccessLevels must contain positive values"
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
                  AND retention_status = 'ACTIVE'
                FOR SHARE
                """,
                (rs, rowNum) -> rs.getInt(1),
                node.documentId(),
                node.accessLevel(),
                node.generation()
        ).stream().findFirst().orElse(null);

        if (published == null) {
            throw new IllegalStateException(
                    "adaptive graph reinforcement requires "
                            + "ACTIVE/PUBLISHED generation"
            );
        }
    }

    private void lockNode(ChunkGraphNode node) {
        jdbcTemplate.query(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)
                )
                """,
                rs -> {
                },
                "akmai:adaptive-graph:node:" + node.lockKey()
        );
    }

    private void upsertPair(
            ChunkGraphNode left,
            ChunkGraphNode right,
            AssociationBand band,
            AssociationEvidence evidence
    ) {
        String sql = """
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
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    set_bit(0::bit(256), ?, 1),
                    1, ?, ?, ?, ?, clock_timestamp()
                ), (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    set_bit(0::bit(256), ?, 1),
                    1, ?, ?, ?, ?, clock_timestamp()
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
                    band = CASE
                        WHEN knowledge_chunk_association.band = 'HOT'
                          OR EXCLUDED.band = 'HOT' THEN 'HOT'
                        WHEN knowledge_chunk_association.band = 'WARM'
                          OR EXCLUDED.band = 'WARM' THEN 'WARM'
                        WHEN knowledge_chunk_association.band = 'CANDIDATE'
                          OR EXCLUDED.band = 'CANDIDATE' THEN 'CANDIDATE'
                        ELSE 'DECAYED'
                    END,
                    weight = greatest(
                        knowledge_chunk_association.weight,
                        EXCLUDED.weight
                    ),
                    support_count =
                        knowledge_chunk_association.support_count
                        + CASE
                            WHEN bit_count(
                                knowledge_chunk_association.query_support_sketch
                                | EXCLUDED.query_support_sketch
                            ) > bit_count(
                                knowledge_chunk_association.query_support_sketch
                            )
                            THEN EXCLUDED.support_count
                            ELSE 0
                          END,
                    context_count =
                        knowledge_chunk_association.context_count
                        + CASE
                            WHEN bit_count(
                                knowledge_chunk_association.query_support_sketch
                                | EXCLUDED.query_support_sketch
                            ) > bit_count(
                                knowledge_chunk_association.query_support_sketch
                            )
                            THEN EXCLUDED.context_count
                            ELSE 0
                          END,
                    citation_count =
                        knowledge_chunk_association.citation_count
                        + CASE
                            WHEN bit_count(
                                knowledge_chunk_association.query_support_sketch
                                | EXCLUDED.query_support_sketch
                            ) > bit_count(
                                knowledge_chunk_association.query_support_sketch
                            )
                            THEN EXCLUDED.citation_count
                            ELSE 0
                          END,
                    distinct_query_support = bit_count(
                        knowledge_chunk_association.query_support_sketch
                        | EXCLUDED.query_support_sketch
                    ),
                    query_support_sketch =
                        knowledge_chunk_association.query_support_sketch
                        | EXCLUDED.query_support_sketch,
                    last_seen_at = greatest(
                        knowledge_chunk_association.last_seen_at,
                        EXCLUDED.last_seen_at
                    ),
                    last_reinforced_at = CASE
                        WHEN bit_count(
                            knowledge_chunk_association.query_support_sketch
                            | EXCLUDED.query_support_sketch
                        ) > bit_count(
                            knowledge_chunk_association.query_support_sketch
                        )
                        THEN greatest(
                            knowledge_chunk_association.last_reinforced_at,
                            EXCLUDED.last_reinforced_at
                        )
                        ELSE knowledge_chunk_association.last_reinforced_at
                    END,
                    band_changed_at = CASE
                        WHEN knowledge_chunk_association.band = 'DECAYED'
                            THEN clock_timestamp()
                        ELSE knowledge_chunk_association.band_changed_at
                    END,
                    decayed_at = NULL,
                    compaction_required = TRUE,
                    updated_at = clock_timestamp()
                """;

        ChunkGraphNode first = left.compareTo(right) <= 0 ? left : right;
        ChunkGraphNode second = first == left ? right : left;

        jdbcTemplate.update(
                sql,
                ps -> {
                    int index = 1;
                    index = bindDirection(
                            ps,
                            index,
                            first,
                            second,
                            band,
                            evidence
                    );
                    bindDirection(
                            ps,
                            index,
                            second,
                            first,
                            band,
                            evidence
                    );
                }
        );
    }

    private int bindDirection(
            java.sql.PreparedStatement ps,
            int index,
            ChunkGraphNode source,
            ChunkGraphNode target,
            AssociationBand band,
            AssociationEvidence evidence
    ) throws java.sql.SQLException {
        Timestamp observed = Timestamp.from(evidence.observedAt());

        ps.setLong(index++, source.accessLevel());
        ps.setString(index++, source.documentId());
        ps.setLong(index++, source.generation());
        ps.setString(index++, source.chunkId());
        ps.setString(index++, target.documentId());
        ps.setLong(index++, target.generation());
        ps.setString(index++, target.chunkId());
        ps.setString(index++, band.name());
        ps.setDouble(index++, evidence.weight());
        ps.setLong(index++, evidence.supportDelta());
        ps.setLong(index++, evidence.contextDelta());
        ps.setLong(index++, evidence.citationDelta());
        ps.setInt(index++, evidence.querySupportBucket());
        ps.setInt(index++, evidence.graphVersion());
        ps.setTimestamp(index++, observed);
        ps.setTimestamp(index++, observed);
        ps.setTimestamp(index++, observed);
        return index;
    }
}
