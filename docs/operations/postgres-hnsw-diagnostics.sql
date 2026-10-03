-- AkmAI PostgreSQL / pgvector production diagnostics.
-- Read-only queries intended for on-call and capacity reviews.

-- HOT projection leaf sizes and tuple health.
SELECT
    child.relname AS leaf,
    pg_total_relation_size(child.oid) AS total_bytes,
    stats.n_live_tup AS estimated_live_rows,
    stats.n_dead_tup AS estimated_dead_rows,
    stats.autovacuum_count,
    stats.last_autovacuum,
    stats.last_analyze
FROM pg_inherits inheritance
JOIN pg_class parent
  ON parent.oid = inheritance.inhparent
JOIN pg_class child
  ON child.oid = inheritance.inhrelid
LEFT JOIN pg_stat_user_tables stats
  ON stats.relid = child.oid
WHERE parent.relname LIKE 'knowledge_search_projection_al_%'
ORDER BY total_bytes DESC, leaf;

-- Vector leaf sizes and HNSW index footprint.
SELECT
    table_schema,
    table_name,
    pg_total_relation_size(
        format('%I.%I', table_schema, table_name)::regclass
    ) AS total_bytes,
    pg_indexes_size(
        format('%I.%I', table_schema, table_name)::regclass
    ) AS index_bytes
FROM information_schema.tables
WHERE table_schema = 'akmai_vector'
  AND table_name ~ '_al_[0-9]+_lang_'
ORDER BY total_bytes DESC, table_name;

-- HNSW indexes and sizes.
SELECT
    schemaname,
    tablename,
    indexname,
    pg_size_pretty(
        pg_relation_size(
            format('%I.%I', schemaname, indexname)::regclass
        )
    ) AS index_size,
    indexdef
FROM pg_indexes
WHERE schemaname = 'akmai_vector'
  AND indexdef ILIKE '%USING hnsw%'
ORDER BY pg_relation_size(
    format('%I.%I', schemaname, indexname)::regclass
) DESC;

-- Retention backlog.
SELECT count(*) AS retention_backlog
FROM knowledge_document_lifecycle
WHERE retention_status = 'ACTIVE'
  AND expires_at IS NOT NULL
  AND expires_at <= clock_timestamp();

-- Retired generations still awaiting verification.
SELECT
    cleanup_status,
    count(*) AS generations,
    min(retired_at) AS oldest_retired_at,
    max(cleanup_attempts) AS max_cleanup_attempts
FROM knowledge_retired_generation
GROUP BY cleanup_status
ORDER BY cleanup_status;

-- Long running transactions that can delay vacuum.
SELECT
    pid,
    usename,
    application_name,
    state,
    clock_timestamp() - xact_start AS transaction_age,
    wait_event_type,
    wait_event,
    left(query, 300) AS query
FROM pg_stat_activity
WHERE xact_start IS NOT NULL
  AND pid <> pg_backend_pid()
ORDER BY xact_start;

-- Database WAL generation since statistics reset.
SELECT
    wal_records,
    wal_fpi,
    wal_bytes,
    stats_reset
FROM pg_stat_wal;
