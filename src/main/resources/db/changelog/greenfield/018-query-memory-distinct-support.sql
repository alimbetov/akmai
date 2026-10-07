--liquibase formatted sql

--changeset akmai-greenfield:018-query-memory-distinct-support
CREATE UNIQUE INDEX uq_rag_query_memory_observation_cluster_fingerprint
    ON rag_query_memory_observation(cluster_id, query_fingerprint);
