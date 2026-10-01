--liquibase formatted sql

--changeset akmai:005-ingestion-recovery
ALTER TABLE knowledge_document_lifecycle
    ADD COLUMN ingestion_started_at TIMESTAMPTZ;

ALTER TABLE knowledge_document_lifecycle
    DROP CONSTRAINT ck_knowledge_lifecycle_status;

ALTER TABLE knowledge_document_lifecycle
    ADD CONSTRAINT ck_knowledge_lifecycle_status
        CHECK (lifecycle_status IN (
            'INGESTING',
            'INGEST_FAILED',
            'READY',
            'DELETE_PENDING',
            'DELETING',
            'DELETE_FAILED',
            'DELETED'
        ));

CREATE INDEX idx_knowledge_lifecycle_stale_ingestion
    ON knowledge_document_lifecycle (ingestion_started_at, document_id)
    WHERE lifecycle_status = 'INGESTING';
