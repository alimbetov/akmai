--liquibase formatted sql

--changeset akmai-greenfield:014-reembedding-ha-lease
ALTER TABLE knowledge_embedding_migration
    ADD COLUMN owner_id VARCHAR(128),
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0;

ALTER TABLE knowledge_embedding_migration
    ADD CONSTRAINT ck_embedding_migration_fencing_token
        CHECK (fencing_token >= 0);

CREATE INDEX idx_embedding_migration_lease
    ON knowledge_embedding_migration(lease_until)
    WHERE migration_status IN (
        'PREPARING',
        'STAGING',
        'READY_TO_CUTOVER'
    );
