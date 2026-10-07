--liquibase formatted sql

--changeset akmai-greenfield:018-query-memory-distinct-support
DELETE FROM rag_query_memory_observation older
USING rag_query_memory_observation newer
WHERE older.cluster_id = newer.cluster_id
  AND older.query_fingerprint = newer.query_fingerprint
  AND (
      older.observed_at < newer.observed_at
      OR (
          older.observed_at = newer.observed_at
          AND older.observation_id < newer.observation_id
      )
  );

CREATE UNIQUE INDEX uq_rag_query_memory_observation_cluster_fingerprint
    ON rag_query_memory_observation(cluster_id, query_fingerprint);
