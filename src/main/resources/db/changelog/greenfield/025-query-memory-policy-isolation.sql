--liquibase formatted sql

--changeset akmai-greenfield:025-query-memory-policy-isolation
ALTER TABLE rag_query_memory_cluster
    ADD COLUMN retrieval_policy_version VARCHAR(128),
    ADD COLUMN learning_policy_version VARCHAR(128),
    ADD COLUMN grounding_policy_version VARCHAR(128);

UPDATE rag_query_memory_cluster
SET retrieval_policy_version = 'legacy-unscoped',
    learning_policy_version = 'legacy-unscoped',
    grounding_policy_version = 'legacy-unscoped'
WHERE retrieval_policy_version IS NULL
   OR learning_policy_version IS NULL
   OR grounding_policy_version IS NULL;

ALTER TABLE rag_query_memory_cluster
    ALTER COLUMN retrieval_policy_version SET NOT NULL,
    ALTER COLUMN learning_policy_version SET NOT NULL,
    ALTER COLUMN grounding_policy_version SET NOT NULL;

ALTER TABLE rag_query_memory_cluster
    ADD CONSTRAINT ck_rag_query_memory_retrieval_policy_non_blank
        CHECK (btrim(retrieval_policy_version) <> ''),
    ADD CONSTRAINT ck_rag_query_memory_learning_policy_non_blank
        CHECK (btrim(learning_policy_version) <> ''),
    ADD CONSTRAINT ck_rag_query_memory_grounding_policy_non_blank
        CHECK (btrim(grounding_policy_version) <> '');

DROP INDEX IF EXISTS idx_rag_query_memory_profile_updated;
DROP INDEX IF EXISTS idx_rag_query_memory_profile_refresh_revision;

CREATE INDEX idx_rag_query_memory_namespace_updated
    ON rag_query_memory_cluster(
        embedding_profile_id,
        retrieval_policy_version,
        learning_policy_version,
        grounding_policy_version,
        updated_at DESC
    );

CREATE INDEX idx_rag_query_memory_namespace_refresh_revision
    ON rag_query_memory_cluster(
        embedding_profile_id,
        retrieval_policy_version,
        learning_policy_version,
        grounding_policy_version,
        refresh_revision
    );
