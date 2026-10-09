--liquibase formatted sql

--changeset akmai-greenfield:029-async-ingestion-worker
CREATE TABLE knowledge_ingestion_job (
    ingestion_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL,

    event_id VARCHAR(200) NOT NULL,
    request_id VARCHAR(200),
    job_fingerprint VARCHAR(64) NOT NULL,
    internal_idempotency_key VARCHAR(200) NOT NULL,

    document_id VARCHAR(100) NOT NULL,
    access_level BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    file_id VARCHAR(200),
    source_version VARCHAR(200) NOT NULL,
    content_hash VARCHAR(80),
    canonical_hash VARCHAR(80),

    payload_mode VARCHAR(32) NOT NULL,
    payload_json JSONB,
    artifact_id VARCHAR(300),

    job_status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,

    lease_owner VARCHAR(200),
    lease_until TIMESTAMPTZ,
    lease_version BIGINT NOT NULL DEFAULT 0,

    generation BIGINT,
    chunk_count INTEGER,
    embedding_profile_id VARCHAR(128),

    last_error_class VARCHAR(64),
    last_error_code VARCHAR(128),
    last_error_message VARCHAR(1000),

    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uk_knowledge_ingestion_job_event UNIQUE (event_id),
    CONSTRAINT uk_knowledge_ingestion_job_fingerprint UNIQUE (job_fingerprint),
    CONSTRAINT uk_knowledge_ingestion_job_internal_idempotency
        UNIQUE (internal_idempotency_key),
    CONSTRAINT ck_knowledge_ingestion_job_schema_version
        CHECK (schema_version > 0),
    CONSTRAINT ck_knowledge_ingestion_job_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_knowledge_ingestion_job_status
        CHECK (job_status IN (
            'ACCEPTED', 'PROCESSING', 'RETRY_WAIT', 'INGESTED', 'FAILED'
        )),
    CONSTRAINT ck_knowledge_ingestion_job_payload_mode
        CHECK (payload_mode IN ('INLINE', 'ARTIFACT_REF')),
    CONSTRAINT ck_knowledge_ingestion_job_payload
        CHECK (
            (payload_mode = 'INLINE' AND payload_json IS NOT NULL)
            OR
            (payload_mode = 'ARTIFACT_REF' AND artifact_id IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_ingestion_job_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_knowledge_ingestion_job_failure_count
        CHECK (failure_count >= 0),
    CONSTRAINT ck_knowledge_ingestion_job_lease_version
        CHECK (lease_version >= 0),
    CONSTRAINT ck_knowledge_ingestion_job_generation
        CHECK (generation IS NULL OR generation > 0),
    CONSTRAINT ck_knowledge_ingestion_job_chunk_count
        CHECK (chunk_count IS NULL OR chunk_count >= 0)
);

CREATE TABLE knowledge_ingestion_job_event (
    event_id VARCHAR(200) PRIMARY KEY,
    ingestion_id UUID NOT NULL REFERENCES knowledge_ingestion_job(ingestion_id)
        ON DELETE CASCADE,
    job_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_knowledge_ingestion_job_claim
    ON knowledge_ingestion_job (job_status, next_attempt_at, accepted_at, ingestion_id);

CREATE INDEX ix_knowledge_ingestion_job_document
    ON knowledge_ingestion_job (document_id, accepted_at DESC);

CREATE UNIQUE INDEX uq_knowledge_ingestion_job_source_version
    ON knowledge_ingestion_job (file_id, source_version)
    WHERE file_id IS NOT NULL;

CREATE INDEX ix_knowledge_ingestion_job_event_ingestion
    ON knowledge_ingestion_job_event (ingestion_id);
