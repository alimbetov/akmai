--liquibase formatted sql

--changeset akmai-greenfield:005-operational
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
        CHECK (request_status IN (
            'IN_PROGRESS',
            'SUCCEEDED',
            'FAILED'
        ))
);

CREATE INDEX idx_ingestion_request_document
    ON knowledge_ingestion_request(
        document_id,
        created_at DESC
    );

CREATE TABLE knowledge_embedding_migration (
    migration_id       UUID PRIMARY KEY,
    source_profile_id  VARCHAR(128) NOT NULL,
    target_profile_id  VARCHAR(128) NOT NULL,
    migration_status   VARCHAR(32) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at       TIMESTAMPTZ,
    last_error         VARCHAR(1000),

    CONSTRAINT ck_embedding_migration_status
        CHECK (migration_status IN (
            'PREPARING',
            'STAGING',
            'READY_TO_CUTOVER',
            'FAILED',
            'COMPLETED'
        )),
    CONSTRAINT fk_embedding_migration_source
        FOREIGN KEY (source_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id),
    CONSTRAINT fk_embedding_migration_target
        FOREIGN KEY (target_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id)
);

CREATE UNIQUE INDEX uq_embedding_migration_active
    ON knowledge_embedding_migration ((1))
    WHERE migration_status IN (
        'PREPARING',
        'STAGING',
        'READY_TO_CUTOVER'
    );

CREATE TABLE knowledge_embedding_migration_document (
    migration_id          UUID NOT NULL,
    document_id           VARCHAR(100) NOT NULL,
    source_generation     BIGINT NOT NULL,
    candidate_generation  BIGINT,
    document_status       VARCHAR(32) NOT NULL DEFAULT 'SNAPSHOT',
    last_error            VARCHAR(1000),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (migration_id, document_id),

    CONSTRAINT fk_embedding_migration_document
        FOREIGN KEY (migration_id)
        REFERENCES knowledge_embedding_migration(migration_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_embedding_migration_document_status
        CHECK (document_status IN (
            'SNAPSHOT',
            'STAGING',
            'VERIFIED',
            'FAILED'
        ))
);

CREATE INDEX idx_embedding_migration_document_status
    ON knowledge_embedding_migration_document(
        migration_id,
        document_status,
        document_id
    );
