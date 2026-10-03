--liquibase formatted sql

--changeset akmai-greenfield:001-platform-control
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS akmai_vector;
CREATE SCHEMA IF NOT EXISTS akmai_admin;

CREATE TABLE knowledge_embedding_profile (
    profile_id              VARCHAR(128) PRIMARY KEY,
    provider                VARCHAR(64) NOT NULL,
    model                   VARCHAR(200) NOT NULL,
    dimensions              INTEGER NOT NULL,
    distance_type           VARCHAR(32) NOT NULL,
    tokenizer_profile       VARCHAR(128) NOT NULL,
    config_fingerprint      VARCHAR(128) NOT NULL,
    vector_schema           VARCHAR(63) NOT NULL DEFAULT 'akmai_vector',
    vector_table            VARCHAR(63) NOT NULL,
    index_type              VARCHAR(32) NOT NULL,
    storage_schema_version  SMALLINT NOT NULL DEFAULT 2,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_embedding_profile_table
        UNIQUE (vector_schema, vector_table),
    CONSTRAINT ck_embedding_profile_dimensions
        CHECK (dimensions > 0 AND dimensions <= 2000),
    CONSTRAINT ck_embedding_profile_distance
        CHECK (distance_type = 'COSINE_DISTANCE'),
    CONSTRAINT ck_embedding_profile_index
        CHECK (index_type IN ('HNSW', 'NONE')),
    CONSTRAINT ck_embedding_profile_vector_table_length
        CHECK (length(vector_table) <= 32)
);

CREATE TABLE knowledge_embedding_runtime (
    singleton_id          SMALLINT PRIMARY KEY,
    active_profile_id     VARCHAR(128),
    migration_profile_id  VARCHAR(128),
    migration_status      VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    row_version           BIGINT NOT NULL DEFAULT 0,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_embedding_runtime_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT ck_embedding_runtime_status
        CHECK (migration_status IN (
            'IDLE',
            'PREPARING',
            'STAGING',
            'READY_TO_CUTOVER'
        )),
    CONSTRAINT fk_embedding_runtime_active
        FOREIGN KEY (active_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id),
    CONSTRAINT fk_embedding_runtime_migration
        FOREIGN KEY (migration_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id)
);

CREATE TABLE knowledge_document_lifecycle (
    document_id           VARCHAR(100) PRIMARY KEY,
    lifecycle_policy      VARCHAR(32) NOT NULL,
    lifecycle_status      VARCHAR(32) NOT NULL,
    generation            BIGINT NOT NULL,
    claim_generation      BIGINT,
    claim_id              UUID,
    claimed_by            VARCHAR(200),
    claimed_at            TIMESTAMPTZ,
    lease_until           TIMESTAMPTZ,
    expires_at            TIMESTAMPTZ,
    delete_started_at     TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ,
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    last_error            VARCHAR(1000),
    row_version           BIGINT NOT NULL DEFAULT 0,
    ingestion_started_at  TIMESTAMPTZ,
    retention_status      VARCHAR(32) NOT NULL,
    published_generation  BIGINT,
    next_generation       BIGINT NOT NULL,
    access_level          BIGINT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_knowledge_lifecycle_policy
        CHECK (lifecycle_policy IN ('PERMANENT', 'TTL')),
    CONSTRAINT ck_knowledge_lifecycle_status
        CHECK (lifecycle_status IN (
            'INGESTING',
            'INGEST_FAILED',
            'READY',
            'DELETE_PENDING',
            'DELETING',
            'DELETE_FAILED',
            'DELETED'
        )),
    CONSTRAINT ck_knowledge_lifecycle_expiration
        CHECK (
            (lifecycle_policy = 'PERMANENT' AND expires_at IS NULL)
            OR
            (lifecycle_policy = 'TTL' AND expires_at IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_retention_status
        CHECK (retention_status IN (
            'ACTIVE',
            'DELETE_PENDING',
            'DELETING',
            'DELETE_FAILED',
            'DELETED'
        )),
    CONSTRAINT ck_knowledge_lifecycle_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_knowledge_lifecycle_generation
        CHECK (generation > 0),
    CONSTRAINT ck_knowledge_next_generation
        CHECK (next_generation > 0),
    CONSTRAINT ck_knowledge_lifecycle_access_level
        CHECK (access_level > 0)
);

CREATE TABLE knowledge_document_generation (
    document_id           VARCHAR(100) NOT NULL,
    generation            BIGINT NOT NULL,
    generation_status     VARCHAR(32) NOT NULL,
    generation_kind       VARCHAR(32) NOT NULL DEFAULT 'INGESTION',
    migration_id          UUID,
    embedding_profile_id  VARCHAR(128),
    content_fingerprint   VARCHAR(64),
    physical_id_version   SMALLINT NOT NULL DEFAULT 2,
    access_level          BIGINT NOT NULL,
    chunk_count           INTEGER,
    failure_code          VARCHAR(64),
    last_error            VARCHAR(1000),
    cleanup_required      BOOLEAN NOT NULL DEFAULT false,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at          TIMESTAMPTZ,
    failed_at             TIMESTAMPTZ,
    retired_at            TIMESTAMPTZ,
    cleaned_at            TIMESTAMPTZ,

    PRIMARY KEY (document_id, generation),

    CONSTRAINT uq_document_generation_acl
        UNIQUE (document_id, generation, access_level),

    CONSTRAINT ck_document_generation_number
        CHECK (generation > 0),
    CONSTRAINT ck_document_generation_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_document_generation_chunk_count
        CHECK (chunk_count IS NULL OR chunk_count >= 0),
    CONSTRAINT ck_document_generation_status
        CHECK (generation_status IN (
            'STAGING',
            'PUBLISHED',
            'FAILED',
            'RETIRED',
            'CLEANED'
        )),
    CONSTRAINT ck_document_generation_kind
        CHECK (generation_kind IN ('INGESTION', 'REEMBEDDING')),
    CONSTRAINT fk_document_generation_profile
        FOREIGN KEY (embedding_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id)
);

CREATE TABLE akmai_retrieval_partition_registry (
    access_level   BIGINT PRIMARY KEY,
    provisioned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_retrieval_partition_access
        CHECK (access_level > 0)
);

CREATE TABLE akmai_supported_language (
    language_code VARCHAR(16) PRIMARY KEY,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    provisioned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_supported_language_code
        CHECK (
            language_code = lower(language_code)
            AND language_code ~ '^[a-z]{2,8}        )
);

        )
);
