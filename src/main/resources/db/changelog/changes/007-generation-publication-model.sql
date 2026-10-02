--liquibase formatted sql

--changeset akmai:007-generation-publication-model
CREATE SCHEMA IF NOT EXISTS akmai_vector;

ALTER TABLE knowledge_document_lifecycle
    ADD COLUMN IF NOT EXISTS retention_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS published_generation BIGINT,
    ADD COLUMN IF NOT EXISTS next_generation BIGINT;

UPDATE knowledge_document_lifecycle
SET retention_status = CASE
        WHEN lifecycle_status = 'DELETE_PENDING' THEN 'DELETE_PENDING'
        WHEN lifecycle_status = 'DELETING' THEN 'DELETING'
        WHEN lifecycle_status = 'DELETE_FAILED' THEN 'DELETE_FAILED'
        WHEN lifecycle_status = 'DELETED' THEN 'DELETED'
        ELSE 'ACTIVE'
    END
WHERE retention_status IS NULL;

UPDATE knowledge_document_lifecycle
SET published_generation = generation
WHERE published_generation IS NULL
  AND lifecycle_status IN ('READY', 'DELETE_PENDING', 'DELETING', 'DELETE_FAILED');

UPDATE knowledge_document_lifecycle
SET next_generation = generation + 1
WHERE next_generation IS NULL;

ALTER TABLE knowledge_document_lifecycle
    ALTER COLUMN retention_status SET NOT NULL,
    ALTER COLUMN retention_status SET DEFAULT 'ACTIVE',
    ALTER COLUMN next_generation SET NOT NULL,
    ALTER COLUMN next_generation SET DEFAULT 1;

ALTER TABLE knowledge_document_lifecycle
    ADD CONSTRAINT ck_knowledge_retention_status
        CHECK (retention_status IN (
            'ACTIVE', 'DELETE_PENDING', 'DELETING', 'DELETE_FAILED', 'DELETED'
        )),
    ADD CONSTRAINT ck_knowledge_next_generation CHECK (next_generation > 0);

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
    storage_schema_version  SMALLINT NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_embedding_profile_table UNIQUE (vector_schema, vector_table),
    CONSTRAINT ck_embedding_profile_dimensions CHECK (dimensions > 0),
    CONSTRAINT ck_embedding_profile_distance CHECK (distance_type = 'COSINE_DISTANCE'),
    CONSTRAINT ck_embedding_profile_index CHECK (index_type IN ('HNSW', 'NONE'))
);

CREATE TABLE knowledge_embedding_runtime (
    singleton_id          SMALLINT PRIMARY KEY DEFAULT 1,
    active_profile_id     VARCHAR(128),
    migration_profile_id  VARCHAR(128),
    migration_status      VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    row_version           BIGINT NOT NULL DEFAULT 0,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_embedding_runtime_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_embedding_runtime_status
        CHECK (migration_status IN ('IDLE', 'PREPARING', 'STAGING', 'READY_TO_CUTOVER')),
    CONSTRAINT fk_embedding_runtime_active
        FOREIGN KEY (active_profile_id) REFERENCES knowledge_embedding_profile(profile_id),
    CONSTRAINT fk_embedding_runtime_migration
        FOREIGN KEY (migration_profile_id) REFERENCES knowledge_embedding_profile(profile_id)
);

INSERT INTO knowledge_embedding_runtime (singleton_id)
VALUES (1)
ON CONFLICT (singleton_id) DO NOTHING;

CREATE TABLE knowledge_document_generation (
    document_id           VARCHAR(100) NOT NULL,
    generation            BIGINT NOT NULL,
    generation_status     VARCHAR(32) NOT NULL,
    generation_kind       VARCHAR(32) NOT NULL DEFAULT 'INGESTION',
    migration_id          UUID,
    embedding_profile_id  VARCHAR(128),
    content_fingerprint   VARCHAR(64),
    physical_id_version   SMALLINT NOT NULL DEFAULT 2,
    failure_code          VARCHAR(64),
    last_error            VARCHAR(1000),
    cleanup_required      BOOLEAN NOT NULL DEFAULT false,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at          TIMESTAMPTZ,
    failed_at             TIMESTAMPTZ,
    retired_at            TIMESTAMPTZ,
    cleaned_at            TIMESTAMPTZ,
    PRIMARY KEY (document_id, generation),
    CONSTRAINT ck_document_generation_number CHECK (generation > 0),
    CONSTRAINT ck_document_generation_status
        CHECK (generation_status IN ('STAGING', 'PUBLISHED', 'FAILED', 'RETIRED', 'CLEANED')),
    CONSTRAINT ck_document_generation_kind
        CHECK (generation_kind IN ('INGESTION', 'REEMBEDDING')),
    CONSTRAINT fk_document_generation_profile
        FOREIGN KEY (embedding_profile_id) REFERENCES knowledge_embedding_profile(profile_id)
);

CREATE UNIQUE INDEX uq_document_generation_published
    ON knowledge_document_generation(document_id)
    WHERE generation_status = 'PUBLISHED';

CREATE INDEX idx_document_generation_stale
    ON knowledge_document_generation(generation_kind, generation_status, started_at, document_id);

INSERT INTO knowledge_document_generation (
    document_id, generation, generation_status, generation_kind,
    embedding_profile_id, physical_id_version, started_at, published_at, failed_at
)
SELECT document_id,
       generation,
       CASE
           WHEN lifecycle_status IN ('READY', 'DELETE_PENDING', 'DELETING', 'DELETE_FAILED')
               THEN 'PUBLISHED'
           ELSE 'FAILED'
       END,
       'INGESTION',
       NULL,
       0,
       COALESCE(ingestion_started_at, created_at),
       CASE
           WHEN lifecycle_status IN ('READY', 'DELETE_PENDING', 'DELETING', 'DELETE_FAILED')
               THEN updated_at
           ELSE NULL
       END,
       CASE
           WHEN lifecycle_status IN ('INGESTING', 'INGEST_FAILED')
               THEN updated_at
           ELSE NULL
       END
FROM knowledge_document_lifecycle
ON CONFLICT (document_id, generation) DO NOTHING;

ALTER TABLE knowledge_search_projection
    ADD COLUMN IF NOT EXISTS generation BIGINT;

UPDATE knowledge_search_projection p
SET generation = COALESCE(l.published_generation, l.generation, 1)
FROM knowledge_document_lifecycle l
WHERE p.document_id = l.document_id
  AND p.generation IS NULL;

UPDATE knowledge_search_projection
SET generation = 1
WHERE generation IS NULL;

ALTER TABLE knowledge_search_projection
    ALTER COLUMN generation SET NOT NULL;

DROP INDEX IF EXISTS uq_knowledge_search_document_chunk_index;
ALTER TABLE knowledge_search_projection
    DROP CONSTRAINT IF EXISTS knowledge_search_projection_pkey;

ALTER TABLE knowledge_search_projection
    ADD PRIMARY KEY (document_id, generation, chunk_id);

CREATE UNIQUE INDEX uq_knowledge_search_document_generation_chunk_index
    ON knowledge_search_projection(document_id, generation, chunk_index);
CREATE INDEX idx_knowledge_search_published_lookup
    ON knowledge_search_projection(document_id, generation, chunk_id);

ALTER TABLE document_identifier
    ADD COLUMN IF NOT EXISTS generation BIGINT;

UPDATE document_identifier i
SET generation = COALESCE(l.published_generation, l.generation, 1)
FROM knowledge_document_lifecycle l
WHERE i.document_id = l.document_id
  AND i.generation IS NULL;

UPDATE document_identifier
SET generation = 1
WHERE generation IS NULL;

ALTER TABLE document_identifier
    ALTER COLUMN generation SET NOT NULL;

ALTER TABLE document_identifier
    DROP CONSTRAINT IF EXISTS uq_document_identifier_occurrence;

ALTER TABLE document_identifier
    ADD CONSTRAINT uq_document_identifier_occurrence_v2
        UNIQUE (document_id, generation, chunk_id, identifier_type, normalized_value);

CREATE INDEX idx_document_identifier_generation
    ON document_identifier(document_id, generation, identifier_type, normalized_value);

ALTER TABLE knowledge_document_vector_generation
    ADD COLUMN IF NOT EXISTS embedding_profile_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS physical_id_version SMALLINT NOT NULL DEFAULT 2;

CREATE TABLE knowledge_reference_target (
    document_id      VARCHAR(100) NOT NULL,
    generation       BIGINT NOT NULL,
    chunk_id         VARCHAR(100) NOT NULL,
    reference_type   VARCHAR(32) NOT NULL,
    canonical_value  VARCHAR(200) NOT NULL,
    raw_value        VARCHAR(500) NOT NULL,
    language         VARCHAR(8) NOT NULL,
    PRIMARY KEY (document_id, generation, chunk_id, reference_type, canonical_value),
    CONSTRAINT uq_reference_target_identity
        UNIQUE (document_id, generation, reference_type, canonical_value)
);

CREATE TABLE knowledge_reference_edge (
    document_id         VARCHAR(100) NOT NULL,
    generation          BIGINT NOT NULL,
    source_chunk_id     VARCHAR(100) NOT NULL,
    reference_type      VARCHAR(32) NOT NULL,
    canonical_value     VARCHAR(200) NOT NULL,
    raw_value           VARCHAR(500) NOT NULL,
    language            VARCHAR(8) NOT NULL,
    target_scope        VARCHAR(32) NOT NULL,
    target_document_id  VARCHAR(100),
    PRIMARY KEY (
        document_id, generation, source_chunk_id,
        reference_type, canonical_value, raw_value
    ),
    CONSTRAINT ck_reference_target_scope
        CHECK (target_scope IN ('SAME_DOCUMENT', 'EXPLICIT_DOCUMENT'))
);

CREATE INDEX idx_reference_edge_source
    ON knowledge_reference_edge(document_id, generation, source_chunk_id);
CREATE INDEX idx_reference_target_lookup
    ON knowledge_reference_target(document_id, generation, reference_type, canonical_value);

CREATE TABLE knowledge_ingestion_request (
    idempotency_key      VARCHAR(200) PRIMARY KEY,
    document_id          VARCHAR(100) NOT NULL,
    request_fingerprint  VARCHAR(64) NOT NULL,
    request_status       VARCHAR(32) NOT NULL,
    generation           BIGINT,
    claim_id             UUID,
    lease_until          TIMESTAMPTZ,
    response_json        JSONB,
    last_error           VARCHAR(1000),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_ingestion_request_status
        CHECK (request_status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_ingestion_request_document
    ON knowledge_ingestion_request(document_id, created_at DESC);

-- Keep the legacy lifecycle_status/generation columns during the remediation branch
-- so historical tests and downgrade diagnostics remain readable. New production
-- code uses retention_status/published_generation/next_generation + generation journal.
