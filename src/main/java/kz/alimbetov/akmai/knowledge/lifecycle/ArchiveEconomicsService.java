package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ArchiveEconomicsService {

    private final JdbcTemplate jdbcTemplate;

    public ArchiveEconomicsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ArchiveEconomicsSnapshot snapshot() {
        ArchiveBacklog backlog = jdbcTemplate.queryForObject(
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
                (rs, rowNum) -> new ArchiveBacklog(
                        rs.getLong("pending_generations"),
                        rs.getLong("pending_chunks"),
                        rs.getLong("oldest_age_seconds")
                )
        );

        Map<String, ArchiveEconomicsSnapshot.StoreFootprint> stores =
                new HashMap<>();
        jdbcTemplate.query(
                """
                WITH archive_leaf AS (
                    SELECT
                        CASE
                            WHEN namespace.nspname = 'public'
                                THEN 'projection'
                            ELSE 'vector'
                        END AS store,
                        relation.oid AS relid,
                        COALESCE(stats.n_live_tup, 0) AS n_live_tup,
                        COALESCE(stats.n_dead_tup, 0) AS n_dead_tup,
                        COALESCE(stats.n_tup_ins, 0) AS n_tup_ins,
                        COALESCE(stats.n_tup_del, 0) AS n_tup_del,
                        COALESCE(stats.autovacuum_count, 0)
                            AS autovacuum_count
                    FROM pg_class relation
                    JOIN pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    LEFT JOIN pg_stat_user_tables stats
                      ON stats.relid = relation.oid
                    WHERE relation.relkind = 'r'
                      AND relation.relispartition
                      AND right(relation.relname, 3) = '_s1'
                      AND (
                          (
                              namespace.nspname = 'public'
                              AND relation.relname LIKE
                                  'knowledge_search_projection_al_%'
                          )
                          OR namespace.nspname = 'akmai_vector'
                      )
                ), sized AS (
                    SELECT
                        store,
                        n_live_tup,
                        n_dead_tup,
                        n_tup_ins,
                        n_tup_del,
                        autovacuum_count,
                        pg_total_relation_size(relid::regclass) AS total_bytes
                    FROM archive_leaf
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
                FROM sized
                GROUP BY store
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        stores.put(
                                rs.getString("store"),
                                new ArchiveEconomicsSnapshot.StoreFootprint(
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

        ArchiveBacklog resolved = backlog == null
                ? new ArchiveBacklog(0, 0, 0)
                : backlog;
        return new ArchiveEconomicsSnapshot(
                Instant.now(),
                resolved.pendingGenerations(),
                resolved.pendingChunks(),
                resolved.oldestAgeSeconds(),
                stores.getOrDefault(
                        "projection",
                        ArchiveEconomicsSnapshot.StoreFootprint.empty()
                ),
                stores.getOrDefault(
                        "vector",
                        ArchiveEconomicsSnapshot.StoreFootprint.empty()
                )
        );
    }

    private record ArchiveBacklog(
            long pendingGenerations,
            long pendingChunks,
            long oldestAgeSeconds
    ) {
    }
}
