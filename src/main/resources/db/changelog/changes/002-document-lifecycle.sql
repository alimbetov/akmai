--liquibase formatted sql

--changeset akmai:002-document-lifecycle
CREATE TABLE knowledge_document_lifecycle (
    document_id        VARCHAR(100) PRIMARY KEY,
    lifecycle_policy   VARCHAR(32) NOT NULL,
    lifecycle_status   VARCHAR(32) NOT NULL,
    generation         BIGINT NOT NULL DEFAULT 1,
    claim_generation   BIGINT,
    claimed_by         VARCHAR(200),
    claimed_at         TIMESTAMPTZ,
    lease_until        TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ,
    delete_started_at  TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ,
    attempt_count      INTEGER NOT NULL DEFAULT 0,
    last_error         VARCHAR(1000),
    row_version        BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_lifecycle_policy
        CHECK (lifecycle_policy IN ('PERMANENT', 'TTL')),
    CONSTRAINT ck_knowledge_lifecycle_status
        CHECK (lifecycle_status IN (
            'READY', 'DELETE_PENDING', 'DELETING', 'DELETE_FAILED', 'DELETED'
        )),
    CONSTRAINT ck_knowledge_lifecycle_expiration
        CHECK (
            (lifecycle_policy = 'PERMANENT' AND expires_at IS NULL)
            OR
            (lifecycle_policy = 'TTL' AND expires_at IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_lifecycle_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_knowledge_lifecycle_generation
        CHECK (generation > 0)
);

CREATE INDEX idx_knowledge_lifecycle_retention
    ON knowledge_document_lifecycle (lifecycle_status, expires_at, document_id)
    WHERE lifecycle_policy = 'TTL';

CREATE INDEX idx_knowledge_lifecycle_claim
    ON knowledge_document_lifecycle (lifecycle_status, lease_until, claim_generation)
    WHERE lifecycle_status IN ('DELETE_PENDING', 'DELETING', 'DELETE_FAILED');
