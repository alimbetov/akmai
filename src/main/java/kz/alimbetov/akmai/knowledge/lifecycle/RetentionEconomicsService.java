package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetentionEconomicsService {

    private final JdbcTemplate jdbcTemplate;

    public RetentionEconomicsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RetentionEconomicsSnapshot snapshot() {
        Backlog backlog = jdbcTemplate.queryForObject(
                """
                SELECT count(*) AS pending_generations,
                       COALESCE(sum(chunk_count), 0) AS pending_chunks,
                       COALESCE(
                           EXTRACT(
                               EPOCH FROM (
                                   clock_timestamp()
                                   - min(coalesce(retired_at, started_at))
                               )
                           )::bigint,
                           0
                       ) AS oldest_age_seconds
                FROM knowledge_document_generation
                WHERE generation_status = 'RETIRED'
                  AND cleanup_required
                """,
                (rs, rowNum) -> new Backlog(
                        rs.getLong("pending_generations"),
                        rs.getLong("pending_chunks"),
                        rs.getLong("oldest_age_seconds")
                )
        );

        Tombstones tombstones = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FILTER (
                           WHERE cleanup_status <> 'VERIFIED'
                       )::bigint AS pending_tombstones,
                       count(*) FILTER (
                           WHERE cleanup_status = 'VERIFIED'
                       )::bigint AS verified_tombstones,
                       pg_total_relation_size(
                           'public.knowledge_retired_generation'::regclass
                       )::bigint AS tombstone_bytes
                FROM knowledge_retired_generation
                """,
                (rs, rowNum) -> new Tombstones(
                        rs.getLong("pending_tombstones"),
                        rs.getLong("verified_tombstones"),
                        rs.getLong("tombstone_bytes")
                )
        );

        Map<String, RetentionEconomicsSnapshot.StoreFootprint> stores =
                new HashMap<>();
        jdbcTemplate.query(
                """
                WITH retrieval_leaf AS (
                    SELECT
                        'projection'::text AS store,
                        tree.relid
                    FROM pg_partition_tree(
                        'public.knowledge_search_projection'::regclass
                    ) tree
                    WHERE tree.isleaf

                    UNION ALL

                    SELECT
                        'vector'::text AS store,
                        tree.relid
                    FROM (
                        SELECT to_regclass(
                                   format(
                                       '%I.%I',
                                       vector_schema,
                                       vector_table
                                   )
                               ) AS parent_rel
                        FROM knowledge_embedding_profile
                        WHERE vector_schema = 'akmai_vector'
                    ) profile
                    CROSS JOIN LATERAL pg_partition_tree(
                        profile.parent_rel
                    ) tree
                    WHERE profile.parent_rel IS NOT NULL
                      AND tree.isleaf
                ), sampled AS (
                    SELECT
                        leaf.store,
                        leaf.relid,
                        COALESCE(stats.n_live_tup, 0) AS n_live_tup,
                        COALESCE(stats.n_dead_tup, 0) AS n_dead_tup,
                        COALESCE(stats.n_tup_ins, 0) AS n_tup_ins,
                        COALESCE(stats.n_tup_del, 0) AS n_tup_del,
                        COALESCE(stats.autovacuum_count, 0)
                            AS autovacuum_count,
                        pg_total_relation_size(leaf.relid)
                            AS total_bytes
                    FROM retrieval_leaf leaf
                    LEFT JOIN pg_stat_user_tables stats
                      ON stats.relid = leaf.relid
                )
                SELECT
                    store,
                    count(*)::bigint AS leaf_count,
                    COALESCE(sum(n_live_tup), 0)::bigint
                        AS estimated_live_rows,
                    COALESCE(sum(n_dead_tup), 0)::bigint
                        AS estimated_dead_rows,
                    COALESCE(sum(n_tup_ins), 0)::bigint
                        AS inserted_rows,
                    COALESCE(sum(n_tup_del), 0)::bigint
                        AS deleted_rows,
                    COALESCE(sum(autovacuum_count), 0)::bigint
                        AS autovacuum_runs,
                    COALESCE(sum(total_bytes), 0)::bigint
                        AS total_bytes,
                    COALESCE(max(total_bytes), 0)::bigint
                        AS max_leaf_bytes
                FROM sampled
                GROUP BY store
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        stores.put(
                                rs.getString("store"),
                                new RetentionEconomicsSnapshot.StoreFootprint(
                                        rs.getLong("estimated_live_rows"),
                                        rs.getLong("estimated_dead_rows"),
                                        rs.getLong("inserted_rows"),
                                        rs.getLong("deleted_rows"),
                                        rs.getLong("autovacuum_runs"),
                                        rs.getLong("total_bytes"),
                                        rs.getLong("leaf_count"),
                                        rs.getLong("max_leaf_bytes")
                                )
                        )
        );

        Backlog resolvedBacklog = backlog == null
                ? new Backlog(0, 0, 0)
                : backlog;
        Tombstones resolvedTombstones = tombstones == null
                ? new Tombstones(0, 0, 0)
                : tombstones;

        return new RetentionEconomicsSnapshot(
                Instant.now(),
                resolvedBacklog.pendingGenerations(),
                resolvedBacklog.pendingChunks(),
                resolvedBacklog.oldestAgeSeconds(),
                resolvedTombstones.pending(),
                resolvedTombstones.verified(),
                resolvedTombstones.bytes(),
                stores.getOrDefault(
                        "projection",
                        RetentionEconomicsSnapshot.StoreFootprint.empty()
                ),
                stores.getOrDefault(
                        "vector",
                        RetentionEconomicsSnapshot.StoreFootprint.empty()
                )
        );
    }

    private record Backlog(
            long pendingGenerations,
            long pendingChunks,
            long oldestAgeSeconds
    ) {
    }

    private record Tombstones(long pending, long verified, long bytes) {
    }
}
