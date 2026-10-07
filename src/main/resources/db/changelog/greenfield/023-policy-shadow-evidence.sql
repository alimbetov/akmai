--liquibase formatted sql

--changeset akmai-greenfield:023-policy-shadow-evidence
CREATE TABLE rag_policy_shadow_observation (
    observation_id         UUID PRIMARY KEY,
    policy_type            VARCHAR(32) NOT NULL,
    policy_version         VARCHAR(128) NOT NULL,
    query_fingerprint      VARCHAR(64) NOT NULL,
    source_fingerprint     VARCHAR(64) NOT NULL,
    query_class            VARCHAR(64) NOT NULL,
    plan_changed           BOOLEAN NOT NULL,
    execution_status       VARCHAR(32) NOT NULL,
    target_chunk_count     INTEGER NOT NULL CHECK (target_chunk_count >= 0),
    found_chunk_count      INTEGER NOT NULL CHECK (found_chunk_count >= 0),
    target_document_count  INTEGER NOT NULL CHECK (target_document_count >= 0),
    found_document_count   INTEGER NOT NULL CHECK (found_document_count >= 0),
    latency_ms             BIGINT NOT NULL CHECK (latency_ms >= 0),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT fk_rag_policy_shadow_policy
        FOREIGN KEY (policy_type, policy_version)
        REFERENCES rag_policy_registry(policy_type, policy_version)
        ON DELETE CASCADE,
    CONSTRAINT ck_rag_policy_shadow_type
        CHECK (policy_type = 'RETRIEVAL'),
    CONSTRAINT ck_rag_policy_shadow_status
        CHECK (execution_status IN (
            'SUCCESS',
            'NO_CHANGE',
            'DEGRADED',
            'CRITICAL_FAILURE',
            'FAILED'
        )),
    CONSTRAINT ck_rag_policy_shadow_chunk_counts
        CHECK (found_chunk_count <= target_chunk_count),
    CONSTRAINT ck_rag_policy_shadow_document_counts
        CHECK (found_document_count <= target_document_count),
    CONSTRAINT uq_rag_policy_shadow_query
        UNIQUE (policy_type, policy_version, query_fingerprint)
);

CREATE INDEX idx_rag_policy_shadow_policy_created
    ON rag_policy_shadow_observation(
        policy_type,
        policy_version,
        created_at DESC
    );

CREATE INDEX idx_rag_policy_shadow_class_created
    ON rag_policy_shadow_observation(
        policy_version,
        query_class,
        created_at DESC
    );
