--liquibase formatted sql

--changeset akmai-greenfield:017-query-memory-ha-revision
CREATE SEQUENCE rag_query_memory_refresh_revision_seq;

ALTER TABLE rag_query_memory_cluster
    ADD COLUMN refresh_revision BIGINT NOT NULL
        DEFAULT nextval('rag_query_memory_refresh_revision_seq');

CREATE INDEX idx_rag_query_memory_profile_refresh_revision
    ON rag_query_memory_cluster(embedding_profile_id, refresh_revision);
